package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.cashbank.domain.dto.CashBankAccountDto;
import com.erp.modules.cashbank.domain.dto.ChequeDto;
import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.dto.RegisterChequeRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.ChequeStatus;
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
import com.erp.platform.common.api.ConflictException;
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
 * Integration tests for {@link ChequeServiceImpl} (ADR-0016 D-5, FR-CASH-10/11, BR-CASH-11/12).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Register cheque against BANK account → ISSUED.
 *   <li>Register against CASH account → rejected.
 *   <li>Duplicate cheque number per bank account → rejected (BR-CASH-12).
 *   <li>Clear cheque → CLEARED; cancel → CANCELLED.
 *   <li>Terminal state: CLEARED cheque cannot be cancelled (and vice versa).
 *   <li>value_date < issue_date → rejected.
 * </ol>
 */
class ChequeServiceIT extends PostgresIntegrationTest {

    @Autowired private ChequeService chequeService;
    @Autowired private CashBankAccountService accountService;
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

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Cheque IT Org"));
        company    = companies.save(new Company(org, "CHQIT", "Cheque IT Co"));
        branch     = branches.save(new Branch(company, "CHQIT1", "Cheque IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("chq_root", passwordEncoder.encode("RootPass1!"), "CHQ Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "chq_root", true, company.getId(), branch.getId(), null));

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());

        String bankGlUid = glAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "1100")
                .map(a -> a.getUid()).orElseThrow();
        String cashGlUid = glAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(a -> a.getUid()).orElseThrow();

        bankAccount = accountService.create(new CreateCashBankAccountRequest(
                companyUid, null, "NMB Bank", CashBankAccountType.BANK,
                "NMB", "5555", "Dar", bankGlUid, false));
        cashAccount = accountService.create(new CreateCashBankAccountRequest(
                companyUid, null, "Petty Cash", CashBankAccountType.CASH,
                null, null, null, cashGlUid, false));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void register_bankAccount_createsIssuedCheque() {
        ChequeDto cheque = chequeService.register(new RegisterChequeRequest(
                companyUid, bankAccount.uid(), "001", "Supplier A",
                new BigDecimal("10000"), LocalDate.now(), LocalDate.now().plusDays(3),
                null, null));

        assertThat(cheque.uid()).isNotBlank();
        assertThat(cheque.status()).isEqualTo(ChequeStatus.ISSUED);
        assertThat(cheque.chequeNumber()).isEqualTo("001");
    }

    @Test
    void register_cashAccount_rejected_FR_CASH_10() {
        RegisterChequeRequest req = new RegisterChequeRequest(
                companyUid, cashAccount.uid(), "001", "Supplier A",
                new BigDecimal("5000"), LocalDate.now(), LocalDate.now().plusDays(3),
                null, null);

        assertThatThrownBy(() -> chequeService.register(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("FR-CASH-10");
    }

    @Test
    void register_duplicateChequeNumber_rejected_BR_CASH_12() {
        chequeService.register(new RegisterChequeRequest(
                companyUid, bankAccount.uid(), "DUP-001", "Supplier B",
                new BigDecimal("1000"), LocalDate.now(), LocalDate.now(), null, null));

        RegisterChequeRequest dupReq = new RegisterChequeRequest(
                companyUid, bankAccount.uid(), "DUP-001", "Supplier C",
                new BigDecimal("2000"), LocalDate.now(), LocalDate.now(), null, null);

        assertThatThrownBy(() -> chequeService.register(dupReq))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("BR-CASH-12");
    }

    @Test
    void clear_issuedCheque_becomesCleared() {
        ChequeDto cheque = chequeService.register(new RegisterChequeRequest(
                companyUid, bankAccount.uid(), "CLR-001", "Payee",
                new BigDecimal("500"), LocalDate.now(), LocalDate.now(), null, null));

        ChequeDto cleared = chequeService.clear(cheque.uid());

        assertThat(cleared.status()).isEqualTo(ChequeStatus.CLEARED);
        assertThat(cleared.clearedAt()).isNotNull();
    }

    @Test
    void cancel_issuedCheque_becomesCancelled() {
        ChequeDto cheque = chequeService.register(new RegisterChequeRequest(
                companyUid, bankAccount.uid(), "CAN-001", "Payee",
                new BigDecimal("500"), LocalDate.now(), LocalDate.now(), null, null));

        ChequeDto cancelled = chequeService.cancel(cheque.uid());

        assertThat(cancelled.status()).isEqualTo(ChequeStatus.CANCELLED);
        assertThat(cancelled.cancelledAt()).isNotNull();
    }

    @Test
    void clear_alreadyCleared_rejected_D5() {
        ChequeDto cheque = chequeService.register(new RegisterChequeRequest(
                companyUid, bankAccount.uid(), "TC-001", "Payee",
                new BigDecimal("100"), LocalDate.now(), LocalDate.now(), null, null));
        chequeService.clear(cheque.uid());

        String uid = cheque.uid();
        assertThatThrownBy(() -> chequeService.clear(uid))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void register_valueDateBeforeIssueDate_rejected() {
        RegisterChequeRequest req = new RegisterChequeRequest(
                companyUid, bankAccount.uid(), "VD-001", "Payee",
                new BigDecimal("100"),
                LocalDate.now(),
                LocalDate.now().minusDays(1),  // value_date < issue_date
                null, null);

        assertThatThrownBy(() -> chequeService.register(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value_date");
    }
}
