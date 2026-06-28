package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.CashTransferDto;
import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.dto.RecordTransferRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for {@link CashTransferServiceImpl} (ADR-0016 D-3, FR-CASH-08, BR-CASH-04).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Transfer persists: two cash_transactions (OUT + IN) + one transfer header.
 *   <li>GL entry is balanced (DR dest-GL / CR source-GL).
 *   <li>Book balance invariant: net cash in the system is unchanged (OUT + IN cancel).
 *   <li>Book balance == linked GL balance (D-9).
 *   <li>Same-account transfer rejected (BR-CASH-04).
 * </ol>
 */
class CashTransferServiceIT extends PostgresIntegrationTest {

    @Autowired private CashTransferService transferService;
    @Autowired private CashBankAccountService accountService;
    @Autowired private CashAccountStatementQuery statementQuery;
    @Autowired private CashGlReconciliationQuery glReconciliationQuery;
    @Autowired private CashTransactionRepository txnRepo;
    @Autowired private JournalLineRepository journalLineRepo;
    @Autowired private ChartOfAccountRepository glAccountRepo;
    @Autowired private ChartOfAccountService chartOfAccountService;
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
    private CashBankAccountDto sourceAccount;
    private CashBankAccountDto destAccount;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("CB Transfer IT Org"));
        company    = companies.save(new Company(org, "CBTIT", "CB Transfer IT Co"));
        branch     = branches.save(new Branch(company, "CBTIT1", "CB Transfer IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("cbt_root", passwordEncoder.encode("RootPass1!"), "CBT Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "cbt_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        String cashGlUid = glAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(a -> a.getUid()).orElseThrow();
        String bankGlUid = glAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "1100")
                .map(a -> a.getUid()).orElseThrow();

        sourceAccount = accountService.create(new CreateCashBankAccountRequest(
                companyUid, null, "Petty Cash", CashBankAccountType.CASH,
                null, null, null, cashGlUid, false));
        destAccount = accountService.create(new CreateCashBankAccountRequest(
                companyUid, null, "Main Bank", CashBankAccountType.BANK,
                "NMB", "9876", "Dar", bankGlUid, false));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void recordTransfer_createsTwoTxnsAndGlEntry() {
        BigDecimal amount = new BigDecimal("5000");
        CashTransferDto transfer = transferService.recordTransfer(new RecordTransferRequest(
                companyUid, sourceAccount.uid(), destAccount.uid(), amount, LocalDate.now(), "T-001"));

        assertThat(transfer.uid()).isNotBlank();
        assertThat(transfer.transferNumber()).startsWith("CBT-");
        assertThat(transfer.journalEntryRef()).isNotBlank();
        assertThat(transfer.outTxnId()).isNotNull();
        assertThat(transfer.inTxnId()).isNotNull();
    }

    @Test
    void recordTransfer_glEntryIsBalanced() {
        BigDecimal amount = new BigDecimal("3000");
        CashTransferDto transfer = transferService.recordTransfer(new RecordTransferRequest(
                companyUid, sourceAccount.uid(), destAccount.uid(), amount, LocalDate.now(), null));

        var entry = journalLineRepo.findAll().stream()
                .filter(l -> l.getCompanyId().equals(company.getId()))
                .toList();

        BigDecimal sumDebit  = entry.stream().map(l -> l.getDebitAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCredit = entry.stream().map(l -> l.getCreditAmount()).reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sumDebit)
                .as("GL entry must be balanced")
                .isEqualByComparingTo(sumCredit);
    }

    @Test
    void recordTransfer_bookBalanceEqualsGlBalance_D9() {
        BigDecimal amount = new BigDecimal("2000");
        transferService.recordTransfer(new RecordTransferRequest(
                companyUid, sourceAccount.uid(), destAccount.uid(), amount, LocalDate.now(), null));

        // D-9: book balance of each account must equal its linked GL account balance
        var sourceRecon = glReconciliationQuery.reconcileOne(sourceAccount.uid());
        var destRecon   = glReconciliationQuery.reconcileOne(destAccount.uid());

        assertThat(sourceRecon.difference())
                .as("source account: book balance must equal linked GL balance (D-9)")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(destRecon.difference())
                .as("destination account: book balance must equal linked GL balance (D-9)")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void recordTransfer_sameAccount_rejected_BR_CASH_04() {
        BigDecimal amount = new BigDecimal("1000");
        RecordTransferRequest sameReq = new RecordTransferRequest(
                companyUid, sourceAccount.uid(), sourceAccount.uid(), amount, LocalDate.now(), null);

        assertThatThrownBy(() -> transferService.recordTransfer(sameReq))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source and destination accounts must be different");
    }
}
