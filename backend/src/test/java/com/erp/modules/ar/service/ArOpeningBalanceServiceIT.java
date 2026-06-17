package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.ArReconciliationDto;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.domain.enums.ArInvoiceSource;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
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
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.CustomerService;
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
 * Integration tests for {@link ArOpeningBalanceServiceImpl} (ADR-0014 D-4/D-6, FR-AR-15).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>setOpeningBalance creates an ar_invoice (source=OPENING_BALANCE) with outstanding == amount;
 *       posts DR AR-control (1200) / CR Opening-Balance-Equity (3100); balanced entry.
 *   <li>GL AR-control balance increases by the opening-balance amount.
 *   <li>RECONCILIATION BAR (ADR-0014 D-8): AR sub-ledger == GL 1200 after an opening balance.
 *   <li>Cross-tenant blocked: principal from company A cannot set an opening balance referencing
 *       a customer in company B (ForbiddenException from ScopeGuard).
 *   <li>GL not configured → operation fails and rolls back (no ar_invoice persisted).
 * </ol>
 */
class ArOpeningBalanceServiceIT extends PostgresIntegrationTest {

    @Autowired private ArOpeningBalanceService openingBalanceService;
    @Autowired private ArReconciliationQuery reconciliationQuery;
    @Autowired private ArGlSeeder arGlSeeder;
    @Autowired private CustomerService customerService;
    @Autowired private ArInvoiceRepository arInvoiceRepo;
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private JournalLineRepository journalLineRepo;
    @Autowired private ChartOfAccountRepository accountRepo;
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
    private String customerUid;

    private static final BigDecimal AMOUNT_5000 = new BigDecimal("5000");
    private static final String TZS = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AR OB IT Org"));
        company    = companies.save(new Company(org, "AROB", "AR OB IT Co"));
        branch     = branches.save(new Branch(company, "AROB1", "AR OB IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("arob_root", passwordEncoder.encode("RootPass1!"), "AROB Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "arob_root", true, company.getId(), branch.getId(), null));

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "OB Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();

        // GL + AR-GL prerequisites
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: ar_invoice created with source=OPENING_BALANCE + balanced GL entry
    // =========================================================================

    @Test
    void setOpeningBalance_createsArInvoice_withOpeningBalanceSource() {
        ArInvoiceDto result = openingBalanceService.setOpeningBalance(
                new SetOpeningBalanceRequest(companyUid, customerUid, AMOUNT_5000,
                        TZS, LocalDate.now(), LocalDate.now().plusDays(30), "OB-001"));

        assertThat(result.source())
                .as("opening balance item must have source=OPENING_BALANCE")
                .isEqualTo(ArInvoiceSource.OPENING_BALANCE);
        assertThat(result.status()).isEqualTo(ArInvoiceStatus.OPEN);
        assertThat(result.originalAmount())
                .isEqualByComparingTo(AMOUNT_5000);
        assertThat(result.outstandingAmount())
                .as("outstanding must equal the opening balance amount")
                .isEqualByComparingTo(AMOUNT_5000);
        assertThat(result.documentNo()).isEqualTo("OB-001");
    }

    @Test
    void setOpeningBalance_postsBalancedGlEntry_drArControl_crOpeningEquity() {
        openingBalanceService.setOpeningBalance(
                new SetOpeningBalanceRequest(companyUid, customerUid, AMOUNT_5000,
                        TZS, LocalDate.now(), LocalDate.now().plusDays(30), null));

        // There must be at least one journal entry for this company now
        var entries = journalEntryRepo.findAll().stream()
                .filter(e -> e.getCompanyId().equals(company.getId()))
                .toList();
        assertThat(entries)
                .as("a GL journal entry must be posted for the opening balance")
                .isNotEmpty();

        // Find the entry whose lines contain the AR-control account debit
        Long arAccountId = accountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "1200")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("AR account 1200 not seeded"));

        Long equityAccountId = accountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "3100")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Opening balance equity 3100 not seeded"));

        boolean foundBalancedEntry = entries.stream().anyMatch(entry -> {
            var lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());
            if (lines.size() < 2) return false;

            BigDecimal sumDebit  = lines.stream()
                    .map(l -> l.getDebitAmount()  != null ? l.getDebitAmount()  : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal sumCredit = lines.stream()
                    .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (sumDebit.compareTo(sumCredit) != 0) return false;
            if (sumDebit.compareTo(AMOUNT_5000) != 0) return false;

            boolean arDebited = lines.stream().anyMatch(
                    l -> arAccountId.equals(l.getAccountId())
                      && l.getDebitAmount() != null
                      && l.getDebitAmount().compareTo(BigDecimal.ZERO) > 0);
            boolean equityCredited = lines.stream().anyMatch(
                    l -> equityAccountId.equals(l.getAccountId())
                      && l.getCreditAmount() != null
                      && l.getCreditAmount().compareTo(BigDecimal.ZERO) > 0);
            return arDebited && equityCredited;
        });

        assertThat(foundBalancedEntry)
                .as("Must find a balanced GL entry: DR 1200 AR-control / CR 3100 Opening-Balance-Equity "
                        + "for amount=" + AMOUNT_5000)
                .isTrue();
    }

    // =========================================================================
    // Bar 2: GL AR-control balance increases by the opening-balance amount
    // =========================================================================

    @Test
    void setOpeningBalance_glArControlBalance_increasedByAmount() {
        // Reconcile before — GL 1200 net should be zero (no prior postings)
        ArReconciliationDto before = reconciliationQuery.reconcile(company.getId());
        BigDecimal glBefore = before.glControlBalance();

        openingBalanceService.setOpeningBalance(
                new SetOpeningBalanceRequest(companyUid, customerUid, AMOUNT_5000,
                        TZS, LocalDate.now(), LocalDate.now().plusDays(30), null));

        // Restore context after potential REQUIRES_NEW boundary
        RequestContext.set(new RequestContext.Principal(
                rootId, "arob_root", true, company.getId(), branch.getId(), null));

        ArReconciliationDto after = reconciliationQuery.reconcile(company.getId());
        BigDecimal glAfter = after.glControlBalance();

        assertThat(glAfter.subtract(glBefore))
                .as("GL AR-control (1200) balance must increase by the opening-balance amount")
                .isEqualByComparingTo(AMOUNT_5000);
    }

    // =========================================================================
    // Bar 3: RECONCILIATION BAR — AR sub-ledger == GL 1200 after opening balance
    // =========================================================================

    @Test
    void setOpeningBalance_reconciliation_subLedgerEqualsGlControl() {
        openingBalanceService.setOpeningBalance(
                new SetOpeningBalanceRequest(companyUid, customerUid, AMOUNT_5000,
                        TZS, LocalDate.now(), LocalDate.now().plusDays(30), null));

        // Restore context after potential REQUIRES_NEW boundary
        RequestContext.set(new RequestContext.Principal(
                rootId, "arob_root", true, company.getId(), branch.getId(), null));

        ArReconciliationDto recon = reconciliationQuery.reconcile(company.getId());

        assertThat(recon.difference())
                .as("RECONCILIATION BAR: AR sub-ledger must equal GL 1200 after an opening balance "
                        + "(ADR-0014 D-8). sub-ledger=" + recon.subLedgerTotal()
                        + " gl-control=" + recon.glControlBalance()
                        + " difference=" + recon.difference())
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(recon.subLedgerTotal())
                .as("sub-ledger must equal the opening-balance amount")
                .isEqualByComparingTo(AMOUNT_5000);
    }

    @Test
    void setOpeningBalance_multipleCustomers_reconciliationStillHolds() {
        // Second customer in the same company
        String customer2Uid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "OB Customer 2",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();

        BigDecimal amount1 = new BigDecimal("3000");
        BigDecimal amount2 = new BigDecimal("7000");

        openingBalanceService.setOpeningBalance(
                new SetOpeningBalanceRequest(companyUid, customerUid, amount1,
                        TZS, LocalDate.now(), LocalDate.now().plusDays(30), null));

        openingBalanceService.setOpeningBalance(
                new SetOpeningBalanceRequest(companyUid, customer2Uid, amount2,
                        TZS, LocalDate.now(), LocalDate.now().plusDays(30), null));

        RequestContext.set(new RequestContext.Principal(
                rootId, "arob_root", true, company.getId(), branch.getId(), null));

        ArReconciliationDto recon = reconciliationQuery.reconcile(company.getId());

        assertThat(recon.difference())
                .as("RECONCILIATION BAR: difference must be zero with two customers' opening balances")
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(recon.subLedgerTotal())
                .as("sub-ledger must equal the sum of both opening balances")
                .isEqualByComparingTo(amount1.add(amount2));
    }

    // =========================================================================
    // Bar 4: Cross-tenant blocked — company A principal cannot reference company B customer
    // =========================================================================

    @Test
    void setOpeningBalance_crossTenant_blocked() {
        // Create a second company + customer in company B
        Organisation org2 = organisations.save(new Organisation("OB Cross Org"));
        Company company2   = companies.save(new Company(org2, "AROB2", "OB Cross Co"));
        Branch branch2     = branches.save(new Branch(company2, "AROB2B", "OB Cross Branch"));

        // Seed a user for company2 for the customer create call
        RequestContext.set(new RequestContext.Principal(
                rootId, "arob_root", true, company2.getId(), branch2.getId(), null));
        String customer2Uid = customerService.create(new CreateCustomerRequest(
                company2.getId(), PartyType.INDIVIDUAL, "OB Cross Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();

        // Switch principal back to company A — attempt to reference company B's customer
        RequestContext.set(new RequestContext.Principal(
                rootId, "arob_root", true, company.getId(), branch.getId(), null));

        // The request uses company A's UID but company B's customer UID
        SetOpeningBalanceRequest crossReq = new SetOpeningBalanceRequest(
                companyUid, customer2Uid, AMOUNT_5000,
                TZS, LocalDate.now(), LocalDate.now().plusDays(30), null);

        // ScopeGuard will resolve company B's id from the customer and reject company A's principal
        assertThatThrownBy(() -> openingBalanceService.setOpeningBalance(crossReq))
                .as("cross-tenant opening balance must be blocked (ScopeGuard or NotFoundException)")
                .isInstanceOf(Exception.class);

        // No ar_invoice must have been persisted for company A
        long countCompanyA = arInvoiceRepo.findAll().stream()
                .filter(i -> i.getCompanyId().equals(company.getId()))
                .count();
        assertThat(countCompanyA)
                .as("no ar_invoice must be created for company A in a cross-tenant attempt")
                .isZero();
    }

    // =========================================================================
    // Bar 5: GL not configured → operation fails and rolls back
    // =========================================================================

    @Test
    void setOpeningBalance_glNotConfigured_rollsBack_noInvoicePersisted() {
        // New company with NO GL seeds
        Organisation org3 = organisations.save(new Organisation("OB No GL Org"));
        Company company3  = companies.save(new Company(org3, "AROB3", "OB No GL Co"));
        Branch branch3    = branches.save(new Branch(company3, "AROB3B", "OB No GL Branch"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "arob_root", true, company3.getId(), branch3.getId(), null));

        String customer3Uid = customerService.create(new CreateCustomerRequest(
                company3.getId(), PartyType.INDIVIDUAL, "OB No GL Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();

        // Deliberately skip chartOfAccountService.seedDefaults / glConfigService.seedDefaults

        SetOpeningBalanceRequest noGlReq = new SetOpeningBalanceRequest(
                company3.getUid(), customer3Uid, AMOUNT_5000,
                TZS, LocalDate.now(), LocalDate.now().plusDays(30), null);

        assertThatThrownBy(() -> openingBalanceService.setOpeningBalance(noGlReq))
                .as("GL not configured must cause setOpeningBalance to fail and roll back")
                .isInstanceOf(Exception.class);

        // No ar_invoice must have been persisted (TX rolled back atomically)
        long countCompany3 = arInvoiceRepo.findAll().stream()
                .filter(i -> i.getCompanyId().equals(company3.getId()))
                .count();
        assertThat(countCompany3)
                .as("ar_invoice must not be persisted when GL posting fails (atomicity, D-4)")
                .isZero();
    }
}
