package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import com.erp.modules.sales.domain.dto.ProfitabilityReportDto;
import com.erp.modules.sales.domain.dto.ProfitabilityRowDto;
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

    // ---- Beans under test ----
    @Autowired private SalesReportQuery         salesReportQuery;
    @Autowired private ProfitabilityReportQuery profitabilityReportQuery;

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
        root.setOrganisationId(org.getId());
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

    /**
     * The branch filter must bind and execute against real Postgres.
     *
     * <p>The on-hand column is scoped by {@code (CAST(? AS BIGINT) IS NULL OR branch_id = ...)} —
     * a construct that only fails at execution time, and every other test here passes a null branch,
     * so the non-null side would otherwise ship unexecuted. It also pins the fix itself: the column
     * used to sum stock company-wide even under a branch filter, so a branch register showed that
     * branch's sales beside the whole company's stock and read as a discrepancy at the counter.
     */
    @Test
    void branchFilter_bindsAndScopesTheOnHandColumnToThatBranch() {
        ProductDto product = stockableProduct("SalesRpt-BranchScope");
        dispatcher.dispatchOne(publishReceiptEvent(
                "RCPT-SR-BR-001", product, new BigDecimal("10"), new BigDecimal("500.00"), 1L));

        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), new BigDecimal("3"));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        setCtx();
        SalesReportDto report = salesReportQuery.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1),
                null, null, null, branch.getUid());   // the previously unexecuted path

        SalesReportRowDto row = report.rows().stream()
                .filter(r -> product.code().equals(r.productCode()))
                .findFirst().orElseThrow();
        assertThat(row.qtySold()).isEqualByComparingTo("3");
        // 10 received less 3 sold, counted at THIS branch.
        assertThat(row.currentStock()).isEqualByComparingTo("7");
    }

    /** An unknown branch uid is a clean 404, never a filter that silently spans branches. */
    @Test
    void branchFilter_unknownUid_isRejected() {
        setCtx();
        assertThatThrownBy(() -> salesReportQuery.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1),
                null, null, null, "NOSUCHBRANCHUID0000000000"))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class);
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
    // BUG #1 regression: null avg_cost → NULL SALE_ISSUE value_amount must not NPE,
    // and must not be reported as a margin either (customer report, 2026-08-27)
    // =========================================================================

    @Test
    void nullCogs_doesNotNpe_andMarginIsReportedAsUnknown() {
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

        // This used to assert margin == net sales, on the reasoning that coalescing the missing
        // cost to zero at least fabricated nothing. It does fabricate something: it reports the
        // entire sale as profit. The customer saw it as a margin that "sometimes seems not
        // correct" — wrong on precisely the products whose stock was never costed, right on the
        // rest. An unknown cost is now reported as an unknown margin.
        BigDecimal net = netSalesFor(product.id());
        assertThat(row.margin())
                .as("margin must be unknown (null), not the whole sale, when the cost was never established")
                .isNull();
        assertThat(row.amount())
                .as("the sale itself is still reported in full — only the margin is unknown")
                .isNotNull();
        assertThat(net).as("sanity: the sale did post net sales").isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void nullCogs_isExcludedFromTheMarginTotal_andCounted() {
        ProductDto product = stockableProduct("SalesRpt-NoCost-Totals");

        setCtx();
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", true));

        setCtx();
        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), new BigDecimal("3"));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        setCtx();
        SalesReportDto report = salesReportQuery.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1),
                null, null, null, null);

        // A total that silently swallowed this row would look complete while omitting it, which is
        // how an overstated profit becomes a number somebody plans against.
        assertThat(report.totals().marginRowsUnknown())
                .as("uncosted rows are counted so the total can be shown as partial")
                .isGreaterThanOrEqualTo(1);
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
    // Profitability Report (K-2026-08-30 #2) — same data shapes, same Postgres.
    //
    // It reads sales_invoice_lines and stock_movements directly, so nothing but a real database
    // proves the SQL binds and the five figures tie together. Hosted here rather than in a class of
    // its own because this scaffold already produces exactly the two shapes that matter: a sale
    // with a known cost, and a sale of stock that was never costed.
    // =========================================================================

    @Test
    void profitability_grossLessVatIsNet_andProfitIsNetLessCostOfSales() {
        ProductDto product = stockableProduct("Profit-Widget");
        BigDecimal receiptCost = new BigDecimal("600.00");
        dispatcher.dispatchOne(publishReceiptEvent(
                "RCPT-PR-001", product, new BigDecimal("10"), receiptCost, 1L));

        BigDecimal avgCost = requireSoh(product.id()).getAvgCost(); // 600
        BigDecimal sellQty = new BigDecimal("4");

        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), sellQty);
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        setCtx();
        ProfitabilityReportDto report = profitabilityReportQuery.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), null);

        List<ProfitabilityRowDto> matches = report.rows().stream()
                .filter(r -> product.code().equals(r.productCode()))
                .toList();
        assertThat(matches).as("exactly one row for the sold product").hasSize(1);
        ProfitabilityRowDto row = matches.get(0);

        // Net derived independently of the DTO under test, so the identities below are real
        // cross-checks rather than restatements of the row's own arithmetic.
        BigDecimal net = netSalesFor(product.id());
        BigDecimal expectedCogs = sellQty.multiply(avgCost);   // 4 × 600 = 2400

        assertThat(row.qtySold()).isEqualByComparingTo(sellQty);
        assertThat(row.netAmount())
                .as("net must be the invoice's own net, not a figure this report re-derives")
                .isEqualByComparingTo(net);
        assertThat(row.grossSales().subtract(row.vatAmount()))
                .as("gross − VAT = net, the identity printed on the page")
                .isEqualByComparingTo(row.netAmount());
        assertThat(row.costOfSales())
                .as("cost of sales is the cost at the moment of sale (avg 600 × qty 4)")
                .isEqualByComparingTo(expectedCogs);
        assertThat(row.profit())
                .as("net − cost of sales = profit, the other identity on the page")
                .isEqualByComparingTo(net.subtract(expectedCogs));

        assertThat(report.totals().rowsWithUnknownCost())
                .as("every cost here is known, so the totals are complete")
                .isZero();
    }

    /**
     * The rule the whole report rests on. Stock sold before it was ever costed has an UNKNOWN cost;
     * carrying it as zero would report the entire sale as profit — the defect already corrected on
     * the Sales Report, and far worse on a report whose only purpose is the profit figure.
     */
    @Test
    void profitability_uncostedStock_reportsUnknown_ratherThanTheWholeSaleAsProfit() {
        ProductDto product = stockableProduct("Profit-NoCost");

        // Backorder opt-in for the same reason as the margin test: the product must stay unreceived
        // for its avg_cost — and so its SALE_ISSUE value_amount — to be null.
        setCtx();
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", true));

        setCtx();
        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), new BigDecimal("3"));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        setCtx();
        ProfitabilityReportDto report = profitabilityReportQuery.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), null);

        ProfitabilityRowDto row = report.rows().stream()
                .filter(r -> product.code().equals(r.productCode()))
                .findFirst().orElseThrow();

        assertThat(row.costOfSales())
                .as("unknown, not zero — a zero cost would report the whole sale as profit")
                .isNull();
        assertThat(row.profit())
                .as("a profit derived from an unknown cost is not reportable either")
                .isNull();
        assertThat(row.grossSales())
                .as("the sale itself is still reported in full — only the cost side is unknown")
                .isGreaterThan(BigDecimal.ZERO);

        // The foot must disclose that it is partial; a total that silently omits this row reads as
        // complete and overstates the profit somebody then plans against.
        assertThat(report.totals().rowsWithUnknownCost()).isGreaterThanOrEqualTo(1);
        assertThat(report.totals().grossSales())
                .as("sales totals still include the uncosted row")
                .isGreaterThanOrEqualTo(row.grossSales());
    }

    /**
     * The branch predicate is appended SQL that only fails at execution time, and every other
     * profitability test passes a null branch — so without this it would ship unexecuted.
     */
    @Test
    void profitability_branchFilter_bindsAndAnUnknownBranchIsRejected() {
        ProductDto product = stockableProduct("Profit-BranchScope");
        dispatcher.dispatchOne(publishReceiptEvent(
                "RCPT-PR-BR-001", product, new BigDecimal("10"), new BigDecimal("500.00"), 1L));

        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), new BigDecimal("3"));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        setCtx();
        ProfitabilityReportDto report = profitabilityReportQuery.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1), branch.getUid());

        assertThat(report.branchName()).isEqualTo(branch.getName());
        assertThat(report.rows())
                .filteredOn(r -> product.code().equals(r.productCode()))
                .hasSize(1);

        setCtx();
        assertThatThrownBy(() -> profitabilityReportQuery.report(company.getId(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(1),
                "NOSUCHBRANCHUID0000000000"))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class);
    }

    /** A backwards date range is refused before it reaches the database. */
    @Test
    void profitability_endBeforeStart_isRejected() {
        setCtx();
        assertThatThrownBy(() -> profitabilityReportQuery.report(company.getId(),
                LocalDate.now(), LocalDate.now().minusDays(1), null))
                .isInstanceOf(IllegalArgumentException.class);
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
