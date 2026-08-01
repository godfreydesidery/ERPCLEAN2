package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.service.ApGlSeeder;
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
import com.erp.modules.products.domain.dto.ProductDto;
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
import com.erp.modules.sales.domain.dto.SalesReportDto;
import com.erp.modules.sales.domain.dto.SalesReportRowDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.modules.stock.service.InventoryGlSeeder;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventStatus;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for {@link SalesReportQuery} (SAM Electronix go-live sales register) — locks
 * in two runtime bugs fixed against real Postgres (Testcontainers).
 *
 * <ol>
 *   <li><b>BUG #1 (NPE):</b> a product sold with no prior receipt has {@code avg_cost} null, so
 *       its {@code SALE_ISSUE} stock movement carries a NULL {@code value_amount}. The per-product
 *       COGS lookup then had a null value against a present key, and subtracting it from net sales
 *       threw a {@code NullPointerException} — a real production crash on this exact data shape.
 *   <li><b>BUG #2 (bad SQL bind):</b> {@code finalised_at} is {@code timestamptz}; the query used
 *       to bind the window with a raw {@link java.time.Instant}, which the PG JDBC driver cannot
 *       type-infer via {@code JdbcTemplate} ("Can't infer the SQL type to use for an instance of
 *       java.time.Instant") — every call threw before returning a single row. Binding
 *       {@code OffsetDateTime} instead is the fix; a query that simply returns (rather than
 *       throwing) proves it.
 * </ol>
 *
 * <p>Scaffold mirrors {@code InventoryValuationServiceIT} (same module foundations, same
 * finalise→dispatch flow) — see that class's javadoc for the singleton-container rationale.
 */
class SalesReportQueryIT extends PostgresIntegrationTest {

    // ---- IAM / org ----
    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    // ---- GL infra (required so the async SALE.FINALISED consumers — stock issue + GL posting —
    // don't anomaly-skip the COGS/inventory postings this query reads back) ----
    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private InventoryGlSeeder       invGlSeeder;

    // ---- Products / Parties / Sales ----
    @Autowired private ProductService          productService;
    @Autowired private PriceListService        priceListService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private CustomerService         customerService;
    @Autowired private AgentService            agentService;
    @Autowired private SalesInvoiceService     salesInvoiceService;
    @Autowired private SalesSettingsService    salesSettingsService;
    @Autowired private TaxRateSeeder           taxRateSeeder;

    // ---- Stock (to read back avg_cost) ----
    @Autowired private StockOnHandRepository   stockOnHandRepo;

    // ---- Bean under test ----
    @Autowired private SalesReportQuery        salesReportQuery;

    // ---- Events ----
    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private TransactionTemplate     txTemplate;

    // ---- Raw read to independently derive "net" (not sourced from the DTO under test) ----
    @Autowired private JdbcTemplate            jdbc;

    // ---- Per-test fixtures ----
    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  pcsUid;
    private String  priceListUid;
    private String  customerUid;
    private String  agentUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("SalesReport IT Org"));
        company = companies.save(new Company(org, "SRPT", "SalesReport IT Co"));
        branch  = branches.save(new Branch(company, "SRPT1", "SalesReport IT Branch"));

        AppUser root = new AppUser("salesreport_root", passwordEncoder.encode("SReport@1!Xx"), "SalesReport Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        setCtx();

        // GL foundations
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        invGlSeeder.seedDefaults(company.getId());

        // Products / Sales foundations
        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();

        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "SalesReport Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "SalesReport Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // BUG #2 regression: timestamptz bind (Instant → OffsetDateTime); margin identity
    // =========================================================================

    @Test
    void happyPath_marginIsNetLessCogs_andBindsDateRange() {
        ProductDto product = stockableProduct("SalesRpt-Widget-A");
        BigDecimal receiptQty  = new BigDecimal("10");
        BigDecimal receiptCost = new BigDecimal("600.00");

        dispatcher.dispatchOne(publishReceiptEvent("RCPT-SR-001", product, receiptQty, receiptCost, 1L));

        BigDecimal avgCost = requireSoh(product.id()).getAvgCost(); // 600

        BigDecimal sellQty = new BigDecimal("4");
        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), sellQty);
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        setCtx();
        // Regresses BUG #2: this query used to bind `finalised_at >= ?`/`< ?` with a raw
        // java.time.Instant, which JdbcTemplate/pgjdbc cannot type-infer, throwing
        // BadSqlGrammarException before ever returning a row. The mere fact this call returns
        // (rather than throwing) proves the OffsetDateTime bind fix.
        SalesReportDto report = salesReportQuery.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1),
                null, null, null, null);

        List<SalesReportRowDto> matches = report.rows().stream()
                .filter(r -> product.code().equals(r.productCode()))
                .toList();
        assertThat(matches).as("exactly one row for the sold product").hasSize(1);
        SalesReportRowDto row = matches.get(0);

        assertThat(row.qtySold())
                .as("qtySold must equal the finalised sale quantity")
                .isEqualByComparingTo(sellQty);

        // Net sales derived independently (direct read, not from the row's own amount/vat fields)
        // so the identity below is a genuine cross-check, not a tautology.
        BigDecimal net = netSalesFor(product.id());
        BigDecimal expectedCogs = sellQty.multiply(avgCost); // 4 × 600 = 2400

        assertThat(row.amount())
                .as("amount (gross) must equal net + vat, VAT-inclusive/exclusive agnostic")
                .isEqualByComparingTo(net.add(row.vat()));
        assertThat(row.margin())
                .as("margin must equal net sales less cost-of-sale at time of sale (avg 600 × qty 4)")
                .isEqualByComparingTo(net.subtract(expectedCogs));
    }

    // =========================================================================
    // BUG #1 regression: null avg_cost → NULL SALE_ISSUE value_amount must not NPE
    // =========================================================================

    @Test
    void nullCogs_doesNotNpe_marginEqualsNet() {
        // Product created but never received → avg_cost stays null, so the SALE_ISSUE movement
        // is written with a NULL value_amount (mirrors
        // InventoryValuationServiceIT#saleIssue_nullAvgCost_cogsLegSkippedQtyStillDeducts) — the
        // exact production-crash data shape for BUG #1.
        ProductDto product = stockableProduct("SalesRpt-NoCost");

        // Deliberate backorder opt-in: this test NEEDS an unreceived product (that is what makes
        // avg_cost null), and NegativeStockGuard would otherwise block the sale before finalise.
        setCtx();
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", true));

        setCtx();
        BigDecimal sellQty = new BigDecimal("3");
        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), sellQty);
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        setCtx();
        // Must NOT throw NullPointerException — regresses BUG #1 (coalesce cogs to ZERO instead of
        // subtracting a null value from net sales).
        SalesReportDto report = salesReportQuery.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1),
                null, null, null, null);

        List<SalesReportRowDto> matches = report.rows().stream()
                .filter(r -> product.code().equals(r.productCode()))
                .toList();
        assertThat(matches).as("product row present despite a null avg_cost").hasSize(1);
        SalesReportRowDto row = matches.get(0);

        BigDecimal net = netSalesFor(product.id());
        assertThat(row.margin())
                .as("margin must equal net sales when COGS is coalesced to zero (no fabricated cost)")
                .isEqualByComparingTo(net);
    }

    // =========================================================================
    // finalised_at window predicate: a sale outside the requested range is excluded
    // =========================================================================

    @Test
    void dateRangeExcludesSale_returnsNoRow() {
        ProductDto product = stockableProduct("SalesRpt-Excluded");
        dispatcher.dispatchOne(publishReceiptEvent("RCPT-SR-EXC-001", product,
                new BigDecimal("10"), new BigDecimal("400"), 1L));

        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), new BigDecimal("2"));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        setCtx();
        SalesReportDto report = salesReportQuery.report(company.getId(),
                LocalDate.now().minusDays(10), LocalDate.now().minusDays(5),
                null, null, null, null);

        assertThat(report.rows())
                .as("finalised_at window predicate must exclude a sale outside the requested range")
                .filteredOn(r -> product.code().equals(r.productCode()))
                .isEmpty();
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void setCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "salesreport_root", true, company.getId(), branch.getId(), null));
    }

    private ProductDto stockableProduct(String name) {
        setCtx();
        ProductDto p = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null));
        productService.setPrice(p.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));
        return p;
    }

    /** Publishes a STOCK.RECEIVED event inside its own TX and returns the new event's id. */
    private Long publishReceiptEvent(String receiptUid, ProductDto product,
                                      BigDecimal qty, BigDecimal cost, Long aggId) {
        StockReceivedPayload payload = new StockReceivedPayload(
                receiptUid, company.getId(), branch.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(
                        product.id(), product.uid(), null, qty, cost)));
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.STOCK_RECEIVED,
                    DomainEventType.AGG_GOODS_RECEIPT, aggId, receiptUid,
                    company.getId(), branch.getId(), payload);
            return null;
        });
        return pendingEvent(DomainEventType.STOCK_RECEIVED);
    }

    private SalesInvoiceDto makeSaleInvoice(String productUid, BigDecimal qty) {
        setCtx();
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, qty, null, null));
        return draft;
    }

    /** Returns the latest PENDING domain event of the given type. */
    private Long pendingEvent(String eventType) {
        return domainEventRepo.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .reduce((a, b) -> b)
                .map(DomainEvent::getId)
                .orElseThrow(() -> new AssertionError("No PENDING event of type: " + eventType));
    }

    private StockOnHand requireSoh(Long productId) {
        return stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(company.getId(), branch.getId(), productId)
                .orElseThrow(() -> new AssertionError("No on-hand row for productId=" + productId));
    }

    /**
     * Independently derives net sales for a product straight from {@code sales_invoice_lines},
     * bypassing the DTO under test entirely — so the amount==net+vat / margin==net-cogs
     * assertions are genuine cross-checks, not tautologies derived from the row's own fields.
     */
    private BigDecimal netSalesFor(Long productId) {
        return jdbc.queryForObject(
                "SELECT COALESCE(SUM(net_amount), 0) FROM sales_invoice_lines WHERE product_id = ?",
                BigDecimal.class, productId);
    }
}
