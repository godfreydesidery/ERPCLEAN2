package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.dto.ApReconciliationDto;
import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
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
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
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
 * Integration tests for {@link ApOpeningBalanceServiceImpl} (ADR-0015 D-8, FR-AP-OB).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>setOpeningBalance creates an OPENING_BALANCE-source bill in MATCHED status;
 *       outstanding == grossAmount; posts DR Opening-Balance-Equity / CR AP-control (balanced).
 *   <li>GL AP-control (2100) net increases by the opening-balance amount.
 *   <li>RECONCILIATION BAR (ADR-0015 D-7/D-8): AP sub-ledger == GL 2100 after an opening balance.
 *   <li>Duplicate opening balance (same supplier + invoice ref) is rejected.
 * </ol>
 */
class ApOpeningBalanceServiceIT extends PostgresIntegrationTest {

    @Autowired private ApOpeningBalanceService openingBalanceService;
    @Autowired private ApReconciliationQuery   reconciliationQuery;
    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private SupplierService         supplierService;
    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private JournalEntryRepository  journalEntryRepo;
    @Autowired private JournalLineRepository   journalLineRepo;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  companyUid;
    private String  supplierUid;

    private static final BigDecimal OB_AMOUNT = new BigDecimal("50000.00");
    private static final String     TZS       = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AP OB IT Org"));
        company    = companies.save(new Company(org, "APOB", "AP OB IT Co"));
        branch     = branches.save(new Branch(company, "APOB1", "AP OB IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("ap_ob_root", passwordEncoder.encode("ApOb00t!Xx"), "AP OB Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "ap_ob_root", true, company.getId(), branch.getId(), null));

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "OB Supplier Ltd",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS)).uid();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: creates OPENING_BALANCE bill, MATCHED, outstanding = gross
    // =========================================================================

    @Test
    void setOpeningBalance_createsBillWithCorrectStatusAndOutstanding() {
        SupplierBillDto dto = openingBalanceService.setOpeningBalance(buildRequest("OB-SUP-001"));

        assertThat(dto.uid()).isNotBlank();
        assertThat(dto.source()).isEqualTo(SupplierBillSource.OPENING_BALANCE);
        assertThat(dto.status()).isEqualTo(SupplierBillStatus.MATCHED);
        assertThat(dto.grossAmount()).isEqualByComparingTo(OB_AMOUNT);
        assertThat(dto.outstandingAmount()).isEqualByComparingTo(OB_AMOUNT);
        assertThat(dto.postedGlEntryUid()).isNotBlank();
    }

    // =========================================================================
    // Bar 2: balanced GL entry: DR Opening Balance Equity / CR AP-control
    // =========================================================================

    @Test
    void setOpeningBalance_postsBalancedGlEntry_drOBE_crAP() {
        SupplierBillDto dto = openingBalanceService.setOpeningBalance(buildRequest("OB-GL-001"));

        var entry = journalEntryRepo.findByUid(dto.postedGlEntryUid())
                .orElseThrow(() -> new AssertionError(
                        "GL entry not found: " + dto.postedGlEntryUid()));

        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());
        assertThat(lines).hasSize(2);

        BigDecimal sumDebit  = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCredit = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(sumDebit).isEqualByComparingTo(sumCredit)
                .as("GL entry must be balanced (D=C)");
        assertThat(sumDebit).isEqualByComparingTo(OB_AMOUNT);

        // Verify OBE account (3100) debited and AP account (2100) credited
        var obeAcct = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "3100");
        assertThat(obeAcct).isPresent().withFailMessage("Account 3100 not seeded");
        var apAcct = accountRepo.findByCompanyIdAndAccountCode(company.getId(), "2100");
        assertThat(apAcct).isPresent().withFailMessage("Account 2100 not seeded");

        boolean obeDebited = lines.stream().anyMatch(l ->
                obeAcct.get().getId().equals(l.getAccountId())
                && l.getDebitAmount() != null
                && l.getDebitAmount().compareTo(BigDecimal.ZERO) > 0);
        assertThat(obeDebited).as("OBE account (3100) must be debited").isTrue();

        boolean apCredited = lines.stream().anyMatch(l ->
                apAcct.get().getId().equals(l.getAccountId())
                && l.getCreditAmount() != null
                && l.getCreditAmount().compareTo(BigDecimal.ZERO) > 0);
        assertThat(apCredited).as("AP control account (2100) must be credited").isTrue();
    }

    // =========================================================================
    // Bar 3: RECONCILIATION BAR — AP sub-ledger == GL 2100 (BR-AP-02)
    // =========================================================================

    @Test
    void reconciliation_afterOpeningBalance_subLedgerEqualsGlControl() {
        openingBalanceService.setOpeningBalance(buildRequest("OB-RECON-001"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "ap_ob_root", true, company.getId(), branch.getId(), null));

        ApReconciliationDto recon = reconciliationQuery.reconcile(company.getId());

        assertThat(recon.difference())
                .as("RECONCILIATION BAR: AP sub-ledger must equal GL 2100 balance (BR-AP-02). "
                        + "sub=" + recon.subLedgerTotal()
                        + " gl=" + recon.glControlBalance()
                        + " diff=" + recon.difference())
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(recon.subLedgerTotal()).isEqualByComparingTo(OB_AMOUNT);
    }

    // =========================================================================
    // Bar 4: Duplicate opening balance rejected
    // =========================================================================

    @Test
    void setOpeningBalance_duplicate_rejected() {
        openingBalanceService.setOpeningBalance(buildRequest("OB-DUP-001"));

        SetApOpeningBalanceRequest dup = buildRequest("OB-DUP-001");
        assertThatThrownBy(() -> openingBalanceService.setOpeningBalance(dup))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("OB-DUP-001");
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private SetApOpeningBalanceRequest buildRequest(String invoiceRef) {
        return new SetApOpeningBalanceRequest(
                companyUid, supplierUid, OB_AMOUNT, TZS,
                LocalDate.now(), LocalDate.now().plusDays(30),
                invoiceRef);
    }
}
