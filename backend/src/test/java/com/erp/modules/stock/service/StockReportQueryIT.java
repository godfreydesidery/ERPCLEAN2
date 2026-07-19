package com.erp.modules.stock.service;

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
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UpdatePriceListRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.dto.StockReportDto;
import com.erp.modules.stock.domain.dto.StockReportRowDto;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for {@link StockReportQuery} (SAM Electronix go-live printable stock listing)
 * against real Postgres (Testcontainers).
 *
 * <p>Scaffold mirrors {@code InventoryValuationServiceIT} (same module foundations, same
 * receipt→dispatch flow) — see that class's javadoc for the singleton-container rationale.
 */
class StockReportQueryIT extends PostgresIntegrationTest {

    // ---- IAM / org ----
    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    // ---- GL infra ----
    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private InventoryGlSeeder       invGlSeeder;

    // ---- Products ----
    @Autowired private ProductService          productService;
    @Autowired private PriceListService        priceListService;
    @Autowired private UnitOfMeasureService    unitService;

    // ---- Stock ----
    @Autowired private StockOnHandRepository   stockOnHandRepo;

    // ---- Bean under test ----
    @Autowired private StockReportQuery        stockReportQuery;

    // ---- Events ----
    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private TransactionTemplate     txTemplate;

    // ---- Per-test fixtures ----
    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  pcsUid;
    private String  priceListUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("StockReport IT Org"));
        company = companies.save(new Company(org, "SKRPT", "StockReport IT Co"));
        branch  = branches.save(new Branch(company, "SKRPT1", "StockReport IT Branch"));

        AppUser root = new AppUser("stockreport_root", passwordEncoder.encode("SKReport@1!Xx"), "StockReport Root");
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

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();

        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Happy path: on-hand qty, avg (buying) cost, default-list selling price, value
    // =========================================================================

    @Test
    void happyPath_rowHasQtyBuyingSellingValue() {
        ProductDto product = stockableProduct("StockRpt-Widget-A");
        BigDecimal qty  = new BigDecimal("10");
        BigDecimal cost = new BigDecimal("500.00");

        dispatcher.dispatchOne(publishReceiptEvent("RCPT-SK-001", product, qty, cost, 1L));

        assertThat(requireSoh(product.id()).getAvgCost())
                .as("sanity: first receipt sets avg_cost to the receipt cost")
                .isEqualByComparingTo(cost);

        // Mark RETAIL as the company DEFAULT price list — StockReportQuery resolves the default
        // list's base-unit price for the "selling price" column via a scalar lookup + fixed-id join.
        setCtx();
        priceListService.updateByUid(priceListUid,
                new UpdatePriceListRequest("Retail", null, null, null, null, true, null));
        // Override the stockableProduct() helper's default 1000 base price with this test's target.
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto("900", "TZS")));

        setCtx();
        StockReportDto report = stockReportQuery.report(company.getId());

        List<StockReportRowDto> matches = report.rows().stream()
                .filter(r -> product.code().equals(r.productCode()))
                .toList();
        assertThat(matches).as("exactly one row for the received product").hasSize(1);
        StockReportRowDto row = matches.get(0);

        assertThat(row.quantityOnHand()).isEqualByComparingTo(qty);
        assertThat(row.buyingPrice())
                .as("buyingPrice must equal the weighted-average cost (avg_cost)")
                .isEqualByComparingTo(cost);
        assertThat(row.sellingPrice())
                .as("sellingPrice must equal the default price list's base-unit price")
                .isEqualByComparingTo(new BigDecimal("900"));
        assertThat(row.value())
                .as("value must equal quantityOnHand × avg_cost = 10 × 500 = 5000")
                .isEqualByComparingTo(new BigDecimal("5000"));
        assertThat(report.totalValue())
                .as("totalValue must include this product's on-hand value")
                .isGreaterThanOrEqualTo(new BigDecimal("5000"));
    }

    // =========================================================================
    // No default price list → sellingPrice null, query must not crash
    // =========================================================================

    @Test
    void noDefaultPriceOrList_sellingPriceNull_noCrash() {
        ProductDto product = stockableProduct("StockRpt-Widget-B");
        BigDecimal qty  = new BigDecimal("5");
        BigDecimal cost = new BigDecimal("300.00");

        dispatcher.dispatchOne(publishReceiptEvent("RCPT-SK-002", product, qty, cost, 1L));

        // No price list is marked default in this test — RETAIL stays is_default=false (the
        // stockableProduct() helper still set a 1000 base price on it, but resolveDefaultPriceListId()
        // finds no default row, so its scalar lookup returns null and the join simply misses).
        setCtx();
        // Must NOT throw.
        StockReportDto report = stockReportQuery.report(company.getId());

        List<StockReportRowDto> matches = report.rows().stream()
                .filter(r -> product.code().equals(r.productCode()))
                .toList();
        assertThat(matches).as("received product row present without a default price list").hasSize(1);
        StockReportRowDto row = matches.get(0);

        assertThat(row.quantityOnHand()).isEqualByComparingTo(qty);
        assertThat(row.buyingPrice()).isEqualByComparingTo(cost);
        assertThat(row.sellingPrice())
                .as("no default price list → sellingPrice must be null, not a crash")
                .isNull();
        assertThat(row.value())
                .as("value must equal quantityOnHand × avg_cost = 5 × 300 = 1500")
                .isEqualByComparingTo(new BigDecimal("1500"));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void setCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "stockreport_root", true, company.getId(), branch.getId(), null));
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
}
