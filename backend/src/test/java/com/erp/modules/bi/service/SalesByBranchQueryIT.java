package com.erp.modules.bi.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.bi.domain.dto.BranchSalesRowDto;
import com.erp.modules.bi.domain.dto.SalesByBranchDto;
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
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
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
 * Integration tests for the Sales-by-Branch BI panel (ADR-0037).
 *
 * <p>Acceptance bars:
 * <ol>
 *   <li>FINALISED invoices in two branches produce correct per-branch totals/counts, sorted DESC.
 *   <li>VOID and DRAFT invoices are excluded from the aggregate.
 *   <li>branchId filter returns only the matching branch row.
 *   <li>No data → grandTotal=0, invoiceCount=0, rows empty.
 * </ol>
 */
class SalesByBranchQueryIT extends PostgresIntegrationTest {

    @Autowired private DashboardService        dashboardService;
    @Autowired private SalesInvoiceService     salesInvoiceService;
    @Autowired private TaxRateSeeder           taxRateSeeder;
    @Autowired private CustomerService         customerService;
    @Autowired private AgentService            agentService;
    @Autowired private ProductService          productService;
    @Autowired private PriceListService        priceListService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private DomainEventRepository   domainEventRepository;
    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    private Company companyA;
    private Branch  branchA1;
    private Branch  branchA2;
    private Long    rootAId;

    private String customerUid;
    private String creditCustomerUid;
    private String agentUid;
    private String productUid;
    private String pcsUid;

    private static final BigDecimal PRODUCT_PRICE = new BigDecimal("2000"); // list price (same for both branches)
    private static final BigDecimal QTY_HIGH      = new BigDecimal("4");    // branch 1 — larger total
    private static final BigDecimal QTY_LOW       = new BigDecimal("1");    // branch 2 — smaller total
    private static final String     TZS           = "TZS";

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("SBB IT Org"));
        companyA  = companies.save(new Company(org, "SBBCO", "SBB IT Co"));
        branchA1  = branches.save(new Branch(companyA, "SBB1", "Branch One"));
        branchA2  = branches.save(new Branch(companyA, "SBB2", "Branch Two"));

        AppUser root = new AppUser("sbb_root", passwordEncoder.encode("SbbR00t1!"), "SBB Root");
        root.setRoot(true);
        root    = users.save(root);
        rootAId = root.getId();

        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branchA1.getId(), null));

        taxRateSeeder.seedDefaults(companyA.getId());

        pcsUid       = unitService.create(new CreateUnitOfMeasureRequest(
                companyA.getUid(), "PCS", "Pieces")).uid();
        String priceListUid = priceListService.create(new CreatePriceListRequest(
                companyA.getUid(), "RETAIL", "Retail")).uid();
        productUid   = productService.create(new CreateProductRequest(
                companyA.getUid(), null, "SBB Widget", null,
                ProductType.GOODS, true, true, pcsUid, null,
                VatStatus.STANDARD, null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto(PRODUCT_PRICE.toPlainString(), TZS)));

        customerUid = customerService.create(new CreateCustomerRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "SBB Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();
        // Credit customer (no credit limit) — used for the VOID case: a credit invoice can be
        // finalised without payment, and only an unpaid invoice can be voided (FLOW-ORDER-TO-CASH-027).
        creditCustomerUid = customerService.create(new CreateCustomerRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "SBB Credit Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();
        agentUid    = agentService.create(new CreateAgentRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "SBB Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1: two branches, correct per-branch totals, sorted DESC by total
    // =========================================================================

    @Test
    void salesByBranch_twoBranches_correctTotalsAndDescOrder() {
        // Post one invoice on branch 1 (higher qty → higher total) and one on branch 2 (lower)
        finaliseInvoiceOnBranch(branchA1, QTY_HIGH);  // 4 × 2000 + VAT (larger)
        finaliseInvoiceOnBranch(branchA2, QTY_LOW);   // 1 × 2000 + VAT (smaller)

        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branchA1.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        SalesByBranchDto dto = dashboardService.salesByBranch(companyA.getId(), null, from, to);

        assertThat(dto).isNotNull();
        assertThat(dto.rows()).hasSize(2);

        // Sorted DESC — branch1 (higher total) must come first
        BranchSalesRowDto first  = dto.rows().get(0);
        BranchSalesRowDto second = dto.rows().get(1);

        assertThat(first.branchId()).isEqualTo(branchA1.getId());
        assertThat(first.branchCode()).isEqualTo("SBB1");
        assertThat(first.branchName()).isEqualTo("Branch One");
        assertThat(first.count()).isEqualTo(1L);
        assertThat(first.total()).isGreaterThan(second.total());

        assertThat(second.branchId()).isEqualTo(branchA2.getId());
        assertThat(second.count()).isEqualTo(1L);

        // grandTotal == sum of both row totals
        BigDecimal expectedGrand = first.total().add(second.total());
        assertThat(dto.grandTotal()).isEqualByComparingTo(expectedGrand);
        assertThat(dto.invoiceCount()).isEqualTo(2L);
        assertThat(dto.currency()).isEqualTo("TZS");
    }

    // =========================================================================
    // Bar 2: VOID and DRAFT are excluded
    // =========================================================================

    @Test
    void salesByBranch_voidAndDraftExcluded() {
        // One FINALISED on branch1
        finaliseInvoiceOnBranch(branchA1, QTY_HIGH);

        // One VOID on branch1 — finalise a CREDIT invoice (no payment) so it can be voided;
        // a paid invoice cannot be voided (FLOW-ORDER-TO-CASH-027).
        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branchA1.getId(), null));
        SalesInvoiceDto draft = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(companyA.getUid(), creditCustomerUid, agentUid, TZS, null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, BigDecimal.ONE, null, null));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatchAll();
        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branchA1.getId(), null));
        salesInvoiceService.voidInvoice(draft.uid(), new VoidInvoiceRequest("test void"));

        // One DRAFT on branch1 (never finalised)
        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branchA1.getId(), null));
        salesInvoiceService.create(
                new CreateSalesInvoiceRequest(companyA.getUid(), customerUid, agentUid, TZS, null, null));

        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branchA1.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        SalesByBranchDto dto = dashboardService.salesByBranch(companyA.getId(), null, from, to);

        // Only 1 FINALISED (non-void) invoice should appear
        assertThat(dto.invoiceCount())
                .as("VOID and DRAFT invoices must be excluded — only FINALISED count")
                .isEqualTo(1L);
        assertThat(dto.rows()).hasSize(1);
        assertThat(dto.rows().get(0).branchId()).isEqualTo(branchA1.getId());
    }

    // =========================================================================
    // Bar 3: branchId filter returns only the matching branch
    // =========================================================================

    @Test
    void salesByBranch_branchFilter_returnsOnlyMatchingBranch() {
        finaliseInvoiceOnBranch(branchA1, QTY_HIGH);
        finaliseInvoiceOnBranch(branchA2, QTY_LOW);

        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branchA1.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        SalesByBranchDto dto = dashboardService.salesByBranch(
                companyA.getId(), branchA1.getId(), from, to);

        assertThat(dto.rows()).hasSize(1);
        assertThat(dto.rows().get(0).branchId()).isEqualTo(branchA1.getId());
        assertThat(dto.invoiceCount()).isEqualTo(1L);
    }

    // =========================================================================
    // Bar 4: no FINALISED invoices → zero result, no exception
    // =========================================================================

    @Test
    void salesByBranch_noFinalisedInvoices_returnsZeroResult() {
        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branchA1.getId(), null));

        LocalDate from = LocalDate.now().withDayOfMonth(1);
        LocalDate to   = LocalDate.now();

        SalesByBranchDto dto = dashboardService.salesByBranch(companyA.getId(), null, from, to);

        assertThat(dto).isNotNull();
        assertThat(dto.rows()).isEmpty();
        assertThat(dto.grandTotal()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(dto.invoiceCount()).isEqualTo(0L);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void finaliseInvoiceOnBranch(Branch branch, BigDecimal qty) {
        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branch.getId(), null));

        SalesInvoiceDto inv = salesInvoiceService.create(
                new CreateSalesInvoiceRequest(companyA.getUid(), customerUid, agentUid, TZS, null, null));
        salesInvoiceService.addLine(inv.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, qty, null, null));

        // grossTotalAmount after line add (includes VAT)
        SalesInvoiceDto refreshed = salesInvoiceService.getByUid(inv.uid());
        BigDecimal gross = refreshed.grossTotalAmount();
        salesInvoiceService.addPayment(inv.uid(),
                new AddPaymentRequest(TenderType.CASH, gross, TZS, null));
        salesInvoiceService.finalise(inv.uid(), new FinaliseInvoiceRequest());
        dispatchAll();

        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branch.getId(), null));
    }

    private void dispatchAll() {
        domainEventRepository.findAll().forEach(e -> {
            try { dispatcher.dispatchOne(e.getId()); } catch (Exception ex) { /* already dispatched */ }
        });
        RequestContext.set(new RequestContext.Principal(
                rootAId, "sbb_root", true, companyA.getId(), branchA1.getId(), null));
    }
}
