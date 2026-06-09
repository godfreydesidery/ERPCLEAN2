package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.cashbank.domain.dto.BankReconciliationDto;
import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CashTransactionDto;
import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.dto.MarkClearedRequest;
import com.erp.modules.cashbank.domain.dto.OpenReconciliationRequest;
import com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.ReconciliationStatus;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link BankReconciliationServiceImpl} (ADR-0016 D-6, FR-CASH-13..15,
 * BR-CASH-06/07/11).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Open a reconciliation for a BANK account → DRAFT status.
 *   <li>CASH account open rejected (BR-CASH-11).
 *   <li>Mark transactions cleared → cleared flag set.
 *   <li>Complete: book == statement → COMPLETED.
 *   <li>Complete: book != statement → rejected (BR-CASH-06).
 *   <li>Cleared flags immutable once COMPLETED (BR-CASH-07).
 * </ol>
 */
class BankReconciliationServiceIT extends PostgresIntegrationTest {

    @Autowired private BankReconciliationService reconService;
    @Autowired private CashBankAccountService accountService;
    @Autowired private CashDirectEntryService directEntryService;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private ChartOfAccountRepository glAccountRepo;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company company;
    private Branch branch;
    private Long rootId;
    private String companyUid;
    private CashBankAccountDto bankAccount;
    private CashBankAccountDto cashAccount;
    private String incomeGlUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Recon IT Org"));
        company    = companies.save(new Company(org, "RCNIT", "Recon IT Co"));
        branch     = branches.save(new Branch(company, "RCNIT1", "Recon IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("rcn_root", passwordEncoder.encode("RootPass1!"), "RCN Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "rcn_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        String bankGlUid = glAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "1100")
                .map(a -> a.getUid()).orElseThrow();
        String cashGlUid = glAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(a -> a.getUid()).orElseThrow();
        // 4100 = Sales Revenue is the seeded income account (V10 CoA seed; no 4000 exists)
        incomeGlUid = glAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "4100")
                .map(a -> a.getUid()).orElseThrow();

        bankAccount = accountService.create(new CreateCashBankAccountRequest(
                companyUid, null, "NMB Bank", CashBankAccountType.BANK,
                "NMB", "111", "Dar", bankGlUid, false));
        cashAccount = accountService.create(new CreateCashBankAccountRequest(
                companyUid, null, "Petty Cash", CashBankAccountType.CASH,
                null, null, null, cashGlUid, false));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void open_bankAccount_createsDraftReconciliation() {
        BankReconciliationDto recon = reconService.open(new OpenReconciliationRequest(
                companyUid, bankAccount.uid(), LocalDate.now(), new BigDecimal("1000")));

        assertThat(recon.uid()).isNotBlank();
        assertThat(recon.status()).isEqualTo(ReconciliationStatus.DRAFT);
        assertThat(recon.reconciliationNumber()).startsWith("REC-");
    }

    @Test
    void open_cashAccount_rejected_BR_CASH_11() {
        OpenReconciliationRequest req = new OpenReconciliationRequest(
                companyUid, cashAccount.uid(), LocalDate.now(), new BigDecimal("500"));

        assertThatThrownBy(() -> reconService.open(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("BR-CASH-11");
    }

    @Test
    void complete_bookEqualsStatement_completesSuccessfully() {
        // Record a direct IN entry so there is a cleared transaction
        BigDecimal amount = new BigDecimal("2000");
        CashTransactionDto txn = directEntryService.recordDirectEntry(new RecordDirectEntryRequest(
                companyUid, bankAccount.uid(), CashTxnDirection.IN, amount,
                LocalDate.now(), incomeGlUid, "Interest income"));

        BankReconciliationDto recon = reconService.open(new OpenReconciliationRequest(
                companyUid, bankAccount.uid(), LocalDate.now(), amount));

        // Mark the transaction as cleared in this reconciliation
        reconService.markCleared(recon.uid(), new MarkClearedRequest(List.of(txn.uid()), true));

        // Complete — book balance (2000 IN) == statement closing balance (2000) → should succeed
        BankReconciliationDto completed = reconService.complete(recon.uid());

        assertThat(completed.status()).isEqualTo(ReconciliationStatus.COMPLETED);
        assertThat(completed.clearedBookBalance()).isEqualByComparingTo(amount);
        assertThat(completed.completedAt()).isNotNull();
    }

    @Test
    void complete_bookDoesNotEqualStatement_rejected_BR_CASH_06() {
        BigDecimal amount = new BigDecimal("1500");
        CashTransactionDto txn = directEntryService.recordDirectEntry(new RecordDirectEntryRequest(
                companyUid, bankAccount.uid(), CashTxnDirection.IN, amount,
                LocalDate.now(), incomeGlUid, "Income"));

        // Open with a DIFFERENT statement closing balance
        BankReconciliationDto recon = reconService.open(new OpenReconciliationRequest(
                companyUid, bankAccount.uid(), LocalDate.now(), new BigDecimal("9999")));

        reconService.markCleared(recon.uid(), new MarkClearedRequest(List.of(txn.uid()), true));

        String reconUid = recon.uid();
        assertThatThrownBy(() -> reconService.complete(reconUid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BR-CASH-06");
    }

    @Test
    void markCleared_afterComplete_rejected_BR_CASH_07() {
        BigDecimal amount = new BigDecimal("500");
        CashTransactionDto txn = directEntryService.recordDirectEntry(new RecordDirectEntryRequest(
                companyUid, bankAccount.uid(), CashTxnDirection.IN, amount,
                LocalDate.now(), incomeGlUid, "Inc"));

        BankReconciliationDto recon = reconService.open(new OpenReconciliationRequest(
                companyUid, bankAccount.uid(), LocalDate.now(), amount));
        reconService.markCleared(recon.uid(), new MarkClearedRequest(List.of(txn.uid()), true));
        reconService.complete(recon.uid());

        // Attempt to modify cleared flag on completed recon
        String reconUid = recon.uid();
        assertThatThrownBy(() -> reconService.markCleared(reconUid,
                new MarkClearedRequest(List.of(txn.uid()), false)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BR-CASH-07");
    }
}
