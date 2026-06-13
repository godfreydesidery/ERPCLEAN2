package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.GlConfigRepository;
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
import com.erp.modules.sales.domain.dto.AddQuotationLineRequest;
import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.CancelSalesOrderRequest;
import com.erp.modules.sales.domain.dto.CreateQuotationRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.QuotationDto;
import com.erp.modules.sales.domain.dto.QuotationLineDto;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.dto.SalesOrderLineDto;
import com.erp.modules.sales.domain.enums.QuotationStatus;
import com.erp.modules.sales.domain.enums.SalesOrderStatus;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.modules.stock.service.InventoryGlSeeder;
import com.erp.modules.ap.service.ApGlSeeder;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Stage-1 O2C integration tests: Quote → SO lifecycle + reservation (ADR-0021).
 *
 * <p>Bars covered:
 * <ol>
 *   <li>BR-SO-01 / Quote→SO: create quotation + line → send → accept → SO exists with copied
 *       lines/pricing, status DRAFT.</li>
 *   <li>BR-SO-02 / Confirm reserves stock: SO confirm increases stock_on_hand.reserved_qty by
 *       qty_ordered_base; available = quantity − reserved decreases; SO status CONFIRMED.</li>
 *   <li>BR-SO-02 / Cancel releases reservation: cancel after confirm zeroes reserved_qty on
 *       stock_on_hand and on SO lines; SO status CANCELLED.</li>
 *   <li>BR-SO-11 guard (discount/VAT): line discount → VAT computed on discounted net.</li>
 * </ol>
 */
class SalesOrderServiceIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private GlConfigRepository      glConfigRepo;
    @Autowired private JournalLineRepository   journalLines;
    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private InventoryGlSeeder       invGlSeeder;

    @Autowired private ProductService          productService;
    @Autowired private PriceListService        priceListService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private CustomerService         customerService;
    @Autowired private AgentService            agentService;
    @Autowired private TaxRateSeeder           taxRateSeeder;

    @Autowired private QuotationService        quotationService;
    @Autowired private SalesOrderService       salesOrderService;

    @Autowired private StockOnHandRepository   stockOnHandRepo;
    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private TransactionTemplate     txTemplate;

    private Company  company;
    private Branch   branch;
    private Long     rootId;
    private String   pcsUid;
    private String   priceListUid;
    private String   customerUid;
    private String   agentUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("SO IT Org"));
        company = companies.save(new Company(org, "SOIT", "SO IT Co"));
        branch  = branches.save(new Branch(company, "SOIT1", "SO IT Branch"));

        AppUser root = new AppUser("so_root", passwordEncoder.encode("S0Root!Xx"), "SO Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        setCtx();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        invGlSeeder.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "SO IT Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "SO IT Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1 — Quote → SO: create quotation + line → send → accept → SO copied
    // =========================================================================

    @Test
    void quoteToSo_copiesLinesAndPricing_soStatusDraft() {
        ProductDto product = stockableProduct("QuoteWidget", "1200");

        // Create DRAFT quotation
        QuotationDto quote = quotationService.create(new CreateQuotationRequest(
                company.getUid(), customerUid, agentUid, "TZS",
                LocalDate.now(), LocalDate.now().plusDays(30),
                null, null, null, null));
        assertThat(quote.status()).isEqualTo(QuotationStatus.DRAFT.name());
        assertThat(quote.quoteNumber()).isNull();

        // Add a line
        QuotationLineDto line = quotationService.addLine(quote.uid(),
                new AddQuotationLineRequest(product.uid(), pcsUid, new BigDecimal("5"),
                        null, null, null));
        assertThat(line.quantity()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(line.unitPriceAmount()).isEqualByComparingTo(new BigDecimal("1200"));

        // Send allocates QUOTE-####
        setCtx();
        QuotationDto sent = quotationService.send(quote.uid());
        assertThat(sent.status()).isEqualTo(QuotationStatus.SENT.name());
        assertThat(sent.quoteNumber()).startsWith("QUOTE-");

        // Accept converts to SO
        setCtx();
        SalesOrderDto so = quotationService.accept(quote.uid());
        assertThat(so.status()).isEqualTo(SalesOrderStatus.DRAFT.name());
        assertThat(so.orderNumber()).startsWith("SO-");
        assertThat(so.sourceQuotationUid()).isEqualTo(quote.uid());

        // Lines copied with quoted pricing
        List<SalesOrderLineDto> soLines = salesOrderService.listLines(so.uid());
        assertThat(soLines).hasSize(1);
        SalesOrderLineDto sol = soLines.get(0);
        assertThat(sol.qtyOrdered()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(sol.unitPriceAmount()).isEqualByComparingTo(new BigDecimal("1200"));

        // Quote is now ACCEPTED with back-link
        setCtx();
        QuotationDto accepted = quotationService.getByUid(quote.uid());
        assertThat(accepted.status()).isEqualTo(QuotationStatus.ACCEPTED.name());
        assertThat(accepted.convertedOrderUid()).isEqualTo(so.uid());
    }

    // =========================================================================
    // Bar 2 — Confirm reserves stock; SO status CONFIRMED
    // =========================================================================

    @Test
    void confirmOrder_reservesStock_statusConfirmed() {
        ProductDto product = stockableProduct("ReserveWidget", "1000");

        // Put 10 units on hand first (so we have a real SOH row to check reserved against)
        publishAndDispatchReceipt(product, new BigDecimal("10"), new BigDecimal("500"));

        StockOnHand sohBefore = requireSoh(product.id());
        assertThat(sohBefore.getReservedQty()).isEqualByComparingTo(BigDecimal.ZERO);

        // Create SO and add a line for 3 units
        SalesOrderDto so = createDirectOrder(product, new BigDecimal("3"));

        setCtx();
        SalesOrderDto confirmed = salesOrderService.confirm(so.uid());
        assertThat(confirmed.status()).isEqualTo(SalesOrderStatus.CONFIRMED.name());

        // reserved_qty increased by qty_ordered_base (3)
        StockOnHand sohAfter = requireSoh(product.id());
        assertThat(sohAfter.getReservedQty())
                .as("reserved_qty must increase by ordered qty after confirm")
                .isEqualByComparingTo(new BigDecimal("3"));

        // available = quantity − reserved = 10 − 3 = 7
        BigDecimal available = sohAfter.getQuantity().subtract(sohAfter.getReservedQty());
        assertThat(available)
                .as("available-to-promise = quantity − reserved_qty")
                .isEqualByComparingTo(new BigDecimal("7"));

        // SO line qty_reserved_base set
        List<SalesOrderLineDto> lines = salesOrderService.listLines(so.uid());
        assertThat(lines.get(0).qtyReservedBase()).isEqualByComparingTo(new BigDecimal("3"));
    }

    // =========================================================================
    // Bar 3 — Cancel after confirm releases reservation
    // =========================================================================

    @Test
    void cancelAfterConfirm_releasesReservation_statusCancelled() {
        ProductDto product = stockableProduct("CancelWidget", "800");

        publishAndDispatchReceipt(product, new BigDecimal("20"), new BigDecimal("400"));

        SalesOrderDto so = createDirectOrder(product, new BigDecimal("5"));

        setCtx();
        salesOrderService.confirm(so.uid());

        StockOnHand sohAfterConfirm = requireSoh(product.id());
        assertThat(sohAfterConfirm.getReservedQty()).isEqualByComparingTo(new BigDecimal("5"));

        setCtx();
        salesOrderService.cancel(so.uid(), new CancelSalesOrderRequest("test cancel"));

        StockOnHand sohAfterCancel = requireSoh(product.id());
        assertThat(sohAfterCancel.getReservedQty())
                .as("reserved_qty must be fully released after cancel")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // SO line qty_reserved_base = 0
        List<SalesOrderLineDto> lines = salesOrderService.listLines(so.uid());
        assertThat(lines.get(0).qtyReservedBase()).isEqualByComparingTo(BigDecimal.ZERO);

        // Status CANCELLED
        setCtx();
        SalesOrderDto cancelled = salesOrderService.getByUid(so.uid());
        assertThat(cancelled.status()).isEqualTo(SalesOrderStatus.CANCELLED.name());
    }

    // =========================================================================
    // Bar 4 (Discount) — line discount → VAT computed on discounted net (BR-SO-10)
    // A 10% line discount on 5 × 1000 = net 4500; VAT 18% = 810; gross 5310.
    // =========================================================================

    @Test
    void lineDiscount_vatComputedOnDiscountedNet() {
        ProductDto product = stockableProduct("DiscWidget", "1000");

        // Create SO with a 10% line discount
        setCtx();
        SalesOrderDto so = salesOrderService.create(new CreateSalesOrderRequest(
                company.getUid(), customerUid, agentUid, "TZS",
                LocalDate.now(), null, null, null, null));

        setCtx();
        salesOrderService.addLine(so.uid(), new AddSalesOrderLineRequest(
                product.uid(), pcsUid, new BigDecimal("5"),
                null, null, new BigDecimal("10")));  // 10% line discount

        setCtx();
        SalesOrderDto refreshed = salesOrderService.getByUid(so.uid());

        // list_price = 1000; unit_price = 1000; lineNet = 1000×5×(1-0.10) = 4500
        BigDecimal expectedNet = new BigDecimal("4500.0000");
        assertThat(refreshed.netTotalAmount())
                .as("net_total must be discounted: 5 × 1000 × 90% = 4500")
                .isEqualByComparingTo(expectedNet);

        // VAT 18% on 4500 = 810
        BigDecimal expectedVat = new BigDecimal("810.0000");
        assertThat(refreshed.vatTotalAmount())
                .as("VAT must be on the discounted net: 4500 × 18% = 810")
                .isEqualByComparingTo(expectedVat);

        assertThat(refreshed.grossTotalAmount())
                .as("gross = net + vat = 4500 + 810 = 5310")
                .isEqualByComparingTo(new BigDecimal("5310.0000"));
    }

    // =========================================================================
    // Bar 5 — Over-reservation (backorder): confirm with no stock still works,
    // available goes negative (BR-SO-05 / OQ-SO-02).
    // =========================================================================

    @Test
    void confirmWithNoStock_overReservation_negativeAvailableAllowed() {
        ProductDto product = stockableProduct("BackorderWidget", "600");
        // No receipt — qty = 0

        SalesOrderDto so = createDirectOrder(product, new BigDecimal("4"));

        setCtx();
        SalesOrderDto confirmed = salesOrderService.confirm(so.uid());
        assertThat(confirmed.status()).isEqualTo(SalesOrderStatus.CONFIRMED.name());

        StockOnHand soh = requireSoh(product.id());
        assertThat(soh.getReservedQty())
                .as("reserved_qty must be set even when qty = 0 (backorder)")
                .isEqualByComparingTo(new BigDecimal("4"));

        BigDecimal available = soh.getQuantity().subtract(soh.getReservedQty());
        assertThat(available)
                .as("available-to-promise is negative (backorder allowed, OQ-SO-02)")
                .isEqualByComparingTo(new BigDecimal("-4"));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void setCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "so_root", true, company.getId(), branch.getId(), null));
    }

    private ProductDto stockableProduct(String name, String price) {
        setCtx();
        ProductDto p = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD));
        productService.setPrice(p.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto(price, "TZS")));
        return p;
    }

    private SalesOrderDto createDirectOrder(ProductDto product, BigDecimal qty) {
        setCtx();
        SalesOrderDto so = salesOrderService.create(new CreateSalesOrderRequest(
                company.getUid(), customerUid, agentUid, "TZS",
                LocalDate.now(), null, null, null, null));
        setCtx();
        salesOrderService.addLine(so.uid(), new AddSalesOrderLineRequest(
                product.uid(), pcsUid, qty, null, null, null));
        return so;
    }

    private void publishAndDispatchReceipt(ProductDto product, BigDecimal qty, BigDecimal cost) {
        StockReceivedPayload payload = new StockReceivedPayload(
                // receipt ref → stock_movements.source_document_uid VARCHAR(26); a 26-char uid, NOT a 34-char "RCPT-SO-"+uid.
                product.uid(), company.getId(), branch.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(
                        product.id(), product.uid(), null, qty, cost)));
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.STOCK_RECEIVED,
                    // aggregate_uid is VARCHAR(26) — use the 26-char product uid, NOT "RCPT-SO-"+uid (34 chars).
                    DomainEventType.AGG_GOODS_RECEIPT, product.id(), product.uid(),
                    company.getId(), branch.getId(), payload);
            return null;
        });
        dispatcher.dispatchOne(pendingEvent(DomainEventType.STOCK_RECEIVED));
    }

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
