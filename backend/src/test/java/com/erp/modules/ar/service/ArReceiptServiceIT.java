package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.ArReconciliationDto;
import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest.AllocationLineRequest;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.domain.enums.ArReceiptStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
import com.erp.modules.cashbank.service.CashBankSeeder;
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
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.SalesSettingsService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventType;
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
 * Integration tests for {@link ArReceiptServiceImpl} (ADR-0014 D-3/D-4, FR-AR-06/07,
 * BR-AR-03/04/05/12, NFR-AR-01).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>Oldest-first auto-allocation: receipt reduces outstanding; PARTIAL/PAID status derived.
 *   <li>Manual override allocation.
 *   <li>On-account (unapplied) receipt — UNALLOCATED status.
 *   <li>Over-allocation rejected (BR-AR-04): amount exceeds outstanding.
 *   <li>Receipt GL entry: balanced DR Cash / CR AR-control in the same TX (D-4).
 *   <li>RECONCILIATION BAR (ADR-0014 D-8): after credit sale + receipt, AR sub-ledger == GL 1200.
 *   <li>GL-not-configured → receipt fails and rolls back (no partial commit).
 *   <li>Closed period → receipt fails and rolls back (no partial commit).
 * </ol>
 */
class ArReceiptServiceIT extends PostgresIntegrationTest {

    @Autowired private ArReceiptService receiptService;
    @Autowired private ArInvoiceService invoiceService;
    @Autowired private ArOpeningBalanceService openingBalanceService;
    @Autowired private ArReconciliationQuery reconciliationQuery;
    @Autowired private ArGlSeeder arGlSeeder;
    @Autowired private SalesInvoiceService salesInvoiceService;
    @Autowired private TaxRateSeeder taxRateSeeder;
    @Autowired private SalesSettingsService salesSettingsService;
    @Autowired private CustomerService customerService;
    @Autowired private AgentService agentService;
    @Autowired private ProductService productService;
    @Autowired private PriceListService priceListService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private DomainEventRepository domainEventRepository;
    @Autowired private DomainEventDispatcher dispatcher;
    @Autowired private ArInvoiceRepository arInvoiceRepo;
    @Autowired private ArReceiptRepository arReceiptRepo;
    @Autowired private JournalEntryRepository journalEntryRepo;
    @Autowired private JournalLineRepository journalLineRepo;
    @Autowired private ChartOfAccountRepository accountRepo;
    @Autowired private ChartOfAccountService chartOfAccountService;
    @Autowired private FiscalCalendarService fiscalCalendarService;
    @Autowired private GlConfigService glConfigService;
    @Autowired private CashBankSeeder cashBankSeeder;
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
    private String creditCustomerUid;
    private String agentUid;
    private String pcsUid;
    private String priceListUid;
    private String productUid;

    /** Gross of one unit at 1000 TZS + 18% VAT = 1180 TZS. */
    private static final BigDecimal GROSS_1180 = new BigDecimal("1180");
    private static final String TZS = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("AR Receipt IT Org"));
        company    = companies.save(new Company(org, "ARRIT", "AR Receipt IT Co"));
        branch     = branches.save(new Branch(company, "ARRIT1", "AR Receipt IT Branch"));
        companyUid = company.getUid();

        AppUser root = new AppUser("arr_root", passwordEncoder.encode("RootPass1!"), "ARR Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootId, "arr_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());

        // This suite is about AR receipts and allocation, not stock — its products are
        // never received. NegativeStockGuard now reads a missing sales_settings row as BLOCK
        // (fail-safe), so opt this company into backorder deliberately.
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", true));

        pcsUid       = unitService.create(
                new CreateUnitOfMeasureRequest(companyUid, "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(
                new CreatePriceListRequest(companyUid, "RETAIL", "Retail")).uid();
        productUid   = productService.create(new CreateProductRequest(
                companyUid, null, "Receipt Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", TZS)));

        var custDto = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Receipt Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));
        creditCustomerUid = custDto.uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "Receipt Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();

        // GL + AR-GL prerequisites
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        arGlSeeder.seedDefaults(company.getId());
        cashBankSeeder.seedDefaults(company.getId());
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1a: full receipt on single open item → PAID
    // =========================================================================

    @Test
    void recordReceipt_fullAmount_invoiceMarkedPaid() {
        ArInvoiceDto inv = creditSaleOpenItem(GROSS_1180);

        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                GROSS_1180, TZS, LocalDate.now(), "CASH", null, List.of()));

        assertThat(receipt.status()).isEqualTo(ArReceiptStatus.ALLOCATED);
        assertThat(receipt.unallocatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(receipt.allocations()).hasSize(1);
        assertThat(receipt.allocations().get(0).allocatedAmount())
                .isEqualByComparingTo(GROSS_1180);

        ArInvoiceDto refreshed = invoiceService.getByUid(inv.uid());
        assertThat(refreshed.status()).isEqualTo(ArInvoiceStatus.PAID);
        assertThat(refreshed.outstandingAmount()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 1b: partial receipt → PARTIAL status on invoice, ALLOCATED on receipt
    // =========================================================================

    @Test
    void recordReceipt_partialAmount_invoiceMarkedPartial() {
        ArInvoiceDto inv = creditSaleOpenItem(GROSS_1180);
        BigDecimal partial = new BigDecimal("500");

        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                partial, TZS, LocalDate.now(), "CASH", null, List.of()));

        assertThat(receipt.status()).isEqualTo(ArReceiptStatus.ALLOCATED);
        assertThat(receipt.unallocatedAmount()).isEqualByComparingTo(BigDecimal.ZERO);

        ArInvoiceDto refreshed = invoiceService.getByUid(inv.uid());
        assertThat(refreshed.status())
                .as("partial payment must move invoice to PARTIAL status")
                .isEqualTo(ArInvoiceStatus.PARTIAL);
        assertThat(refreshed.outstandingAmount())
                .isEqualByComparingTo(GROSS_1180.subtract(partial));
    }

    // =========================================================================
    // Bar 1c: oldest-first when two open items exist
    // =========================================================================

    @Test
    void recordReceipt_twoOpenItems_oldestFirst() {
        // First open item: 1000 TZS (oldest)
        ArInvoiceDto older = openItemViaOpeningBalance(new BigDecimal("1000"),
                LocalDate.now().minusDays(10));
        // Second open item: 1180 TZS (newer)
        ArInvoiceDto newer = creditSaleOpenItem(GROSS_1180);

        // Receipt for 1000 — must go to the oldest item first
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                new BigDecimal("1000"), TZS, LocalDate.now(), "CASH", null, List.of()));

        assertThat(receipt.allocations()).hasSize(1);
        assertThat(receipt.allocations().get(0).arInvoiceUid()).isEqualTo(older.uid());

        ArInvoiceDto refreshedOlder = invoiceService.getByUid(older.uid());
        assertThat(refreshedOlder.status()).isEqualTo(ArInvoiceStatus.PAID);

        ArInvoiceDto refreshedNewer = invoiceService.getByUid(newer.uid());
        assertThat(refreshedNewer.status()).isEqualTo(ArInvoiceStatus.OPEN);
    }

    // =========================================================================
    // Bar 2: Manual override allocation
    // =========================================================================

    @Test
    void recordReceipt_manualAllocation_allocatesToSpecifiedInvoice() {
        ArInvoiceDto inv1 = openItemViaOpeningBalance(new BigDecimal("1000"),
                LocalDate.now().minusDays(5));
        ArInvoiceDto inv2 = creditSaleOpenItem(GROSS_1180);

        // Manually target the newer invoice (not the oldest)
        BigDecimal allocAmt = new BigDecimal("500");
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                allocAmt, TZS, LocalDate.now(), "CASH", null,
                List.of(new AllocationLineRequest(inv2.uid(), allocAmt))));

        assertThat(receipt.allocations()).hasSize(1);
        assertThat(receipt.allocations().get(0).arInvoiceUid()).isEqualTo(inv2.uid());

        // inv1 must be untouched
        assertThat(invoiceService.getByUid(inv1.uid()).status()).isEqualTo(ArInvoiceStatus.OPEN);
        // inv2 must be PARTIAL
        assertThat(invoiceService.getByUid(inv2.uid()).status()).isEqualTo(ArInvoiceStatus.PARTIAL);
    }

    // =========================================================================
    // Bar 3: On-account (unapplied) receipt — UNALLOCATED, no open items
    // =========================================================================

    @Test
    void recordReceipt_noOpenItems_receiptIsUnallocated() {
        // No open items for this customer — receipt goes on-account
        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                new BigDecimal("2000"), TZS, LocalDate.now(), "BANK_TRANSFER", "REF-001", List.of()));

        assertThat(receipt.status())
                .as("receipt with no invoices to allocate must be UNALLOCATED (BR-AR-05)")
                .isEqualTo(ArReceiptStatus.UNALLOCATED);
        assertThat(receipt.unallocatedAmount()).isEqualByComparingTo(new BigDecimal("2000"));
        assertThat(receipt.allocations()).isEmpty();
        assertThat(receipt.glEntryUid())
                .as("GL entry must be posted even for an on-account receipt")
                .isNotBlank();
    }

    // =========================================================================
    // Bar 4: Over-allocation rejected (BR-AR-04)
    // =========================================================================

    @Test
    void recordReceipt_overAllocation_rejected_BR_AR_04() {
        ArInvoiceDto inv = creditSaleOpenItem(GROSS_1180);

        // Try to allocate MORE than the invoice outstanding (S5778: build request outside lambda)
        BigDecimal tooMuch = GROSS_1180.add(new BigDecimal("1"));
        RecordReceiptRequest overAllocReq = new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                tooMuch, TZS, LocalDate.now(), "CASH", null,
                List.of(new AllocationLineRequest(inv.uid(), tooMuch)));

        assertThatThrownBy(() -> receiptService.recordAndAllocate(overAllocReq))
                .as("over-allocation must be rejected (BR-AR-04)")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("exceeds invoice outstanding");
    }

    // =========================================================================
    // Bar 5: Receipt posts DR Cash / CR AR-control — balanced journal entry
    // =========================================================================

    @Test
    void recordReceipt_postsBalancedGlEntry_drCash_crArControl() {
        creditSaleOpenItem(GROSS_1180);

        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                GROSS_1180, TZS, LocalDate.now(), "CASH", null, List.of()));

        assertThat(receipt.glEntryUid())
                .as("receipt must reference a posted GL entry")
                .isNotBlank();

        var entry = journalEntryRepo.findByUid(receipt.glEntryUid())
                .orElseThrow(() -> new AssertionError(
                        "GL entry not found for uid=" + receipt.glEntryUid()));

        var lines = journalLineRepo.findByEntryIdOrderByLineNo(entry.getId());
        assertThat(lines).hasSizeGreaterThanOrEqualTo(2);

        BigDecimal sumDebit = lines.stream()
                .map(l -> l.getDebitAmount() != null ? l.getDebitAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal sumCredit = lines.stream()
                .map(l -> l.getCreditAmount() != null ? l.getCreditAmount() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        // S5853: chain related assertions on sumDebit
        assertThat(sumDebit)
                .as("receipt journal entry must be balanced: DR == CR")
                .isEqualByComparingTo(sumCredit)
                .as("DR side must equal the receipt amount")
                .isEqualByComparingTo(GROSS_1180);

        // Verify the Cash account is debited (account code 1000)
        Long cashAccountId = accountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "1000")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("Cash account 1000 not seeded"));

        boolean cashDebited = lines.stream()
                .anyMatch(l -> cashAccountId.equals(l.getAccountId())
                            && l.getDebitAmount() != null
                            && l.getDebitAmount().compareTo(BigDecimal.ZERO) > 0);
        assertThat(cashDebited)
                .as("Cash account (1000) must be debited in the receipt GL entry")
                .isTrue();

        // Verify AR control (1200) is credited
        Long arAccountId = accountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "1200")
                .map(ChartOfAccount::getId)
                .orElseThrow(() -> new AssertionError("AR account 1200 not seeded"));

        boolean arCredited = lines.stream()
                .anyMatch(l -> arAccountId.equals(l.getAccountId())
                            && l.getCreditAmount() != null
                            && l.getCreditAmount().compareTo(BigDecimal.ZERO) > 0);
        assertThat(arCredited)
                .as("AR control account (1200) must be credited in the receipt GL entry")
                .isTrue();
    }

    // =========================================================================
    // Bar 6: RECONCILIATION BAR — AR sub-ledger == GL 1200 control (ADR-0014 D-8)
    // =========================================================================

    @Test
    void reconciliation_afterCreditSaleAndReceipt_subLedgerEqualsGlControl() {
        // Step 1: create a credit sale → dispatch SALE.FINALISED → open item created
        SalesInvoiceDto invoice = creditSaleWithNoPayment();
        dispatcher.dispatchOne(getSaleFinalisedEvent(invoice.uid()).getId());

        // Restore context after dispatcher's REQUIRES_NEW boundary
        RequestContext.set(new RequestContext.Principal(
                rootId, "arr_root", true, company.getId(), branch.getId(), null));

        // Step 2: verify open item exists
        var openItems = arInvoiceRepo.findAll().stream()
                .filter(i -> invoice.uid().equals(i.getSourceInvoiceUid()))
                .toList();
        assertThat(openItems)
                .as("open item must exist after SALE.FINALISED dispatch")
                .hasSize(1);

        // Step 3: record a partial receipt (500 TZS)
        BigDecimal receiptAmt = new BigDecimal("500");
        receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                receiptAmt, TZS, LocalDate.now(), "CASH", null, List.of()));

        // Restore context after the receipt service's TX
        RequestContext.set(new RequestContext.Principal(
                rootId, "arr_root", true, company.getId(), branch.getId(), null));

        // Step 4: RECONCILIATION BAR — sub-ledger must equal GL 1200
        ArReconciliationDto recon = reconciliationQuery.reconcile(company.getId());

        assertThat(recon.difference())
                .as("RECONCILIATION BAR: AR sub-ledger total must equal GL AR-control balance "
                        + "(ADR-0014 D-8). sub-ledger=" + recon.subLedgerTotal()
                        + " gl-control=" + recon.glControlBalance()
                        + " difference=" + recon.difference())
                .isEqualByComparingTo(BigDecimal.ZERO);

        assertThat(recon.subLedgerTotal())
                .as("sub-ledger must equal gross minus receipt")
                .isEqualByComparingTo(GROSS_1180.subtract(receiptAmt));
    }

    @Test
    void reconciliation_afterFullReceipt_bothBalancesAreZero() {
        // Full credit sale
        SalesInvoiceDto invoice = creditSaleWithNoPayment();
        dispatcher.dispatchOne(getSaleFinalisedEvent(invoice.uid()).getId());

        RequestContext.set(new RequestContext.Principal(
                rootId, "arr_root", true, company.getId(), branch.getId(), null));

        // Full receipt
        receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                GROSS_1180, TZS, LocalDate.now(), "CASH", null, List.of()));

        RequestContext.set(new RequestContext.Principal(
                rootId, "arr_root", true, company.getId(), branch.getId(), null));

        ArReconciliationDto recon = reconciliationQuery.reconcile(company.getId());

        assertThat(recon.difference())
                .as("RECONCILIATION BAR: difference must be zero after full settlement")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // After full payment the invoice is PAID — outstanding = 0; GL 1200 net = 0
        assertThat(recon.subLedgerTotal())
                .as("sub-ledger must be zero after full receipt")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(recon.glControlBalance())
                .as("GL 1200 net must be zero after cash receipt clears the debit")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // Bar 7: GL not configured → receipt fails and rolls back
    // =========================================================================

    @Test
    void recordReceipt_glNotConfigured_rollsBack_nothingCommitted() {
        // Set up a company with NO GL seeds — GL resolver will throw
        Organisation org2 = organisations.save(new Organisation("AR No GL Org"));
        Company company2  = companies.save(new Company(org2, "ARNGL", "AR No GL Co"));
        Branch branch2    = branches.save(new Branch(company2, "ARNGL1", "AR No GL Branch"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "arr_root", true, company2.getId(), branch2.getId(), null));
        taxRateSeeder.seedDefaults(company2.getId());

        var cust2 = customerService.create(new CreateCustomerRequest(
                company2.getId(), PartyType.INDIVIDUAL, "No GL Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));

        // Deliberately skip chartOfAccountService.seedDefaults and glConfigService.seedDefaults

        // S5778: build request outside lambda so only recordAndAllocate can throw
        RecordReceiptRequest noGlReq = new RecordReceiptRequest(
                company2.getUid(), cust2.uid(),
                new BigDecimal("500"), TZS, LocalDate.now(), "CASH", null, List.of());

        assertThatThrownBy(() -> receiptService.recordAndAllocate(noGlReq))
                .as("GL not configured must cause receipt to fail and roll back")
                .isInstanceOf(Exception.class);

        // No receipt record persisted (the whole TX rolled back)
        long receiptsAfter = arReceiptRepo
                .findByCompanyId(company2.getId(), org.springframework.data.domain.Pageable.unpaged())
                .getTotalElements();
        assertThat(receiptsAfter)
                .as("receipt must not be persisted when GL posting fails (atomicity, D-4)")
                .isZero();
    }

    // =========================================================================
    // Finding #2 (MEDIUM / BR-NOTIF-13): PAYMENT.RECEIVED payload carries
    //   formatted amount (e.g. "TZS 1,180.00") not a raw BigDecimal string
    // =========================================================================

    @Test
    void recordReceipt_outboxPayload_amountFormattedNotRawDecimal() {
        // Need an open item to allocate against
        creditSaleOpenItem(GROSS_1180);

        ArReceiptDto receipt = receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                GROSS_1180, TZS, LocalDate.now(), "CASH", null, List.of()));

        // Retrieve the PAYMENT.RECEIVED event for this receipt from the outbox
        var paymentEvent = domainEventRepository.findAll().stream()
                .filter(e -> DomainEventType.PAYMENT_RECEIVED.equals(e.getEventType())
                          && receipt.uid().equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No PAYMENT.RECEIVED event found for receipt uid=" + receipt.uid()));

        String payload = paymentEvent.getPayload();
        assertThat(payload)
                .as("PAYMENT.RECEIVED payload must contain a formatted amount (BR-NOTIF-13), not raw decimal.\n"
                        + "payload=" + payload)
                .contains("TZS 1,180.00");
        assertThat(payload)
                .as("Raw toPlainString() value '1180.0000' or '1180' must NOT appear as amountFormatted (BR-NOTIF-13)")
                .doesNotContain("\"amountFormatted\":\"1180");
    }

    // =========================================================================
    // Bar 8: Closed fiscal period → receipt fails and rolls back
    // =========================================================================

    @Test
    void recordReceipt_closedPeriod_rollsBack_nothingCommitted() {
        creditSaleOpenItem(GROSS_1180);

        // Close the current period
        var periods = fiscalCalendarService.listPeriods(company.getId());
        assertThat(periods).isNotEmpty();
        // Find the period that covers today
        var currentPeriod = periods.stream()
                .filter(p -> !p.startDate().isAfter(LocalDate.now())
                          && !p.endDate().isBefore(LocalDate.now()))
                .findFirst()
                .orElseGet(() -> periods.get(0));
        fiscalCalendarService.closePeriod(currentPeriod.uid());

        assertThatThrownBy(() -> receiptService.recordAndAllocate(new RecordReceiptRequest(
                companyUid, creditCustomerUid,
                GROSS_1180, TZS, LocalDate.now(), "CASH", null, List.of())))
                .as("posting into a closed period must fail and roll back (BR-GL-03, D-4)")
                .isInstanceOf(Exception.class);

        // The invoice must remain OPEN (TX rolled back)
        var openItems = arInvoiceRepo.findAll().stream()
                .filter(i -> i.getCompanyId().equals(company.getId())
                          && ArInvoiceStatus.OPEN.equals(i.getStatus()))
                .toList();
        assertThat(openItems)
                .as("open item must remain OPEN after failed receipt (TX rollback)")
                .hasSize(1);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Creates a credit sale, dispatches SALE.FINALISED, and returns the resulting AR open item.
     * The sale has a gross of the specified amount (uses opening balance to avoid GL complexity).
     */
    private ArInvoiceDto creditSaleOpenItem(BigDecimal amount) {
        return openItemViaOpeningBalance(amount, LocalDate.now());
    }

    /**
     * Creates an opening-balance AR open item for the credit customer (simpler than
     * full GL-seeded credit sale for tests that just need an open item to allocate against).
     */
    private ArInvoiceDto openItemViaOpeningBalance(BigDecimal amount, LocalDate date) {
        return openingBalanceService.setOpeningBalance(new SetOpeningBalanceRequest(
                companyUid, creditCustomerUid, amount, TZS, date, date.plusDays(30), null));
    }

    /**
     * Creates and finalises a credit sale with no upfront payment (outstanding = gross = 1180).
     * Returns the SalesInvoiceDto — the open item is created only after SALE.FINALISED dispatch.
     */
    private SalesInvoiceDto creditSaleWithNoPayment() {
        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(companyUid, creditCustomerUid, agentUid, TZS, null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, new BigDecimal("1"), null, null));
        // Credit customer: no payment required at finalise (ADR-0014 D-10)
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        return salesInvoiceService.getByUid(draft.uid());
    }

    private com.erp.platform.events.DomainEvent getSaleFinalisedEvent(String invoiceUid) {
        return domainEventRepository.findAll().stream()
                .filter(e -> DomainEventType.SALE_FINALISED.equals(e.getEventType())
                          && invoiceUid.equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError(
                        "No SALE.FINALISED event found for invoice uid=" + invoiceUid));
    }

}
