package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.service.ApGlSeeder;
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
import com.erp.modules.sales.domain.dto.AddSalesOrderLineRequest;
import com.erp.modules.sales.domain.dto.CreateDeliveryRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.DeliveryDto;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.dto.SalesOrderLineDto;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.domain.enums.SalesOrderStatus;
import com.erp.modules.sales.domain.enums.TenderType;
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
import java.math.RoundingMode;
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
 * Stage-1 O2C integration tests: Delivery → COGS seam → invoice-from-delivery (ADR-0021).
 *
 * <p>Bars covered:
 * <ol>
 *   <li>BR-SO-04 / Partial delivery → COGS + backorder: partial qty delivered → stock deducted +
 *       COGS journal DR 5100/CR 1300 at avg cost; reservation for delivered qty released; unshipped
 *       balance remains; SO → PARTIALLY_FULFILLED.</li>
 *   <li>BR-SO-09 / THE SEAM (critical): invoice-from-delivery (origin=SALES_ORDER) → finalise →
 *       revenue posted but stock qty + on_hand_value UNCHANGED. Capture before/after and assert equal.</li>
 *   <li>BR-SO-09 regression / DIRECT walk-in invoice STILL issues stock on finalise (issuesStock=true).</li>
 *   <li>BR-SO-11 guards: cannot deliver more than open qty; cannot invoice more than delivered.</li>
 * </ol>
 */
class DeliveryServiceIT extends PostgresIntegrationTest {

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

    @Autowired private SalesOrderService       salesOrderService;
    @Autowired private DeliveryService         deliveryService;
    @Autowired private SalesInvoiceService     salesInvoiceService;

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

        Organisation org = organisations.save(new Organisation("Del IT Org"));
        company = companies.save(new Company(org, "DELI", "Del IT Co"));
        branch  = branches.save(new Branch(company, "DELI1", "Del IT Branch"));

        AppUser root = new AppUser("del_root", passwordEncoder.encode("D3lRoot!Xx"), "Del Root");
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
                company.getId(), PartyType.INDIVIDUAL, "Del IT Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "Del IT Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1 — Partial delivery → COGS + backorder; SO → PARTIALLY_FULFILLED
    // =========================================================================

    @Test
    void partialDelivery_postsCogs_releasesReservation_backorderRemains() {
        // Receipt: 10 @ 600 → avg_cost = 600
        ProductDto product = stockableProduct("DelWidget", "1500");
        publishAndDispatchReceipt(product, new BigDecimal("10"), new BigDecimal("600"));

        BigDecimal avgCost = requireSoh(product.id()).getAvgCost();
        assertThat(avgCost).isEqualByComparingTo(new BigDecimal("600"));

        // Confirm SO for 10 units
        SalesOrderDto so = createAndConfirmOrder(product, new BigDecimal("10"));
        assertThat(requireSoh(product.id()).getReservedQty()).isEqualByComparingTo(new BigDecimal("10"));

        // Deliver only 4 (partial)
        List<SalesOrderLineDto> soLines = salesOrderService.listLines(so.uid());
        String solUid = soLines.get(0).uid();

        setCtx();
        DeliveryDto delivery = deliveryService.create(new CreateDeliveryRequest(
                so.uid(), LocalDate.now(), null,
                List.of(new CreateDeliveryRequest.DeliveryLineRequest(solUid, new BigDecimal("4")))));
        assertThat(delivery.deliveryNumber()).startsWith("DEL-");

        // Dispatch DELIVERY.CONFIRMED → stock issues + COGS
        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_CONFIRMED));

        // Stock quantity deducted by 4: 10 − 4 = 6
        StockOnHand soh = requireSoh(product.id());
        assertThat(soh.getQuantity())
                .as("stock qty must be deducted by delivered qty")
                .isEqualByComparingTo(new BigDecimal("6"));

        // COGS DR = 4 × 600 = 2400
        BigDecimal expectedCogs = new BigDecimal("4").multiply(avgCost)
                .setScale(4, RoundingMode.HALF_UP);
        assertThat(cogsBalance())
                .as("COGS must be DR at qty × avg_cost for delivery")
                .isEqualByComparingTo(expectedCogs);

        // Inventory CR = 2400; net Inventory = receipt DR 6000 − COGS CR 2400 = 3600
        BigDecimal expectedInvNet = new BigDecimal("10").multiply(new BigDecimal("600"))
                .subtract(expectedCogs)
                .setScale(4, RoundingMode.HALF_UP);
        assertThat(inventoryBalance())
                .as("Inventory GL net after receipt and partial delivery")
                .isEqualByComparingTo(expectedInvNet);

        // Reservation released for the delivered qty: 10 − 4 = 6 still reserved
        assertThat(requireSoh(product.id()).getReservedQty())
                .as("reserved_qty decreases by delivered qty")
                .isEqualByComparingTo(new BigDecimal("6"));

        // SO line: qty_fulfilled_base = 4; open = 6 (backorder)
        setCtx();
        List<SalesOrderLineDto> refreshedLines = salesOrderService.listLines(so.uid());
        SalesOrderLineDto sol = refreshedLines.get(0);
        assertThat(sol.qtyFulfilledBase()).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(sol.openQtyBase()).isEqualByComparingTo(new BigDecimal("6"));

        // SO status → PARTIALLY_FULFILLED
        setCtx();
        SalesOrderDto refreshedSo = salesOrderService.getByUid(so.uid());
        assertThat(refreshedSo.status()).isEqualTo(SalesOrderStatus.PARTIALLY_FULFILLED.name());
    }

    // =========================================================================
    // Bar 2 — THE SEAM: invoice-from-delivery → finalise → revenue only; stock UNCHANGED
    //         COGS posted exactly once (at delivery). sourceRef = deliveryUid.
    // =========================================================================

    @Test
    void invoiceFromDelivery_postsRevenueOnly_stockUnchangedByFinalise() {
        // Receipt: 20 @ 500 → avg = 500
        ProductDto product = stockableProduct("SeamWidget", "1200");
        publishAndDispatchReceipt(product, new BigDecimal("20"), new BigDecimal("500"));

        SalesOrderDto so = createAndConfirmOrder(product, new BigDecimal("10"));
        List<SalesOrderLineDto> soLines = salesOrderService.listLines(so.uid());
        String solUid = soLines.get(0).uid();

        // Deliver all 10
        setCtx();
        DeliveryDto delivery = deliveryService.create(new CreateDeliveryRequest(
                so.uid(), LocalDate.now(), null,
                List.of(new CreateDeliveryRequest.DeliveryLineRequest(solUid, new BigDecimal("10")))));

        // Dispatch DELIVERY.CONFIRMED — issues stock, posts COGS
        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_CONFIRMED));

        // Capture stock state BEFORE invoice finalise
        StockOnHand sohBeforeInvoice = requireSoh(product.id());
        BigDecimal qtyBefore   = sohBeforeInvoice.getQuantity();
        BigDecimal valueBefore = sohBeforeInvoice.getOnHandValue();
        BigDecimal cogsBefore  = cogsBalance();

        assertThat(qtyBefore).isEqualByComparingTo(new BigDecimal("10"));   // 20 − 10 delivered
        assertThat(cogsBefore).isEqualByComparingTo(
                new BigDecimal("10").multiply(new BigDecimal("500")).setScale(4, RoundingMode.HALF_UP));

        // Create invoice from delivery (origin=SALES_ORDER)
        setCtx();
        SalesInvoiceDto draftInv = deliveryService.createInvoiceFromDelivery(delivery.uid());
        assertThat(draftInv.status()).isEqualTo(InvoiceStatus.DRAFT);

        // Finalise the invoice (CREDIT customer — no cash payment required)
        // Switch to a credit customer for simplicity; or add payment for the gross amount
        setCtx();
        BigDecimal grossAmt = salesInvoiceService.getByUid(draftInv.uid()).grossTotalAmount();
        salesInvoiceService.addPayment(draftInv.uid(),
                new AddPaymentRequest(TenderType.CASH, grossAmt, "TZS", null));
        setCtx();
        salesInvoiceService.finalise(draftInv.uid(), new FinaliseInvoiceRequest());

        // Dispatch SALE.FINALISED — SaleIssueStockHandler must SKIP (issuesStock=false)
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        // Stock qty + on_hand_value UNCHANGED by the invoice finalise (seam invariant BR-SO-09)
        StockOnHand sohAfterInvoice = requireSoh(product.id());
        assertThat(sohAfterInvoice.getQuantity())
                .as("SEAM: stock qty must NOT change on invoice finalise (delivery already issued)")
                .isEqualByComparingTo(qtyBefore);
        assertThat(sohAfterInvoice.getOnHandValue())
                .as("SEAM: on_hand_value must NOT change on invoice finalise")
                .isEqualByComparingTo(valueBefore);

        // COGS balance unchanged — no double-post (BR-SO-09, the impossibility argument D-6)
        assertThat(cogsBalance())
                .as("SEAM: COGS must NOT be posted again at invoice finalise (already posted at delivery)")
                .isEqualByComparingTo(cogsBefore);

        // Revenue IS posted (GL: DR Cash / CR Sales Revenue + VAT)
        assertThat(revenueBalance().negate())
                .as("SEAM: Sales Revenue GL must be credited at finalise")
                .isGreaterThan(BigDecimal.ZERO);

        // SO → FULFILLED then PARTIALLY_INVOICED / CLOSED after full invoice
        setCtx();
        SalesOrderDto finalSo = salesOrderService.getByUid(so.uid());
        assertThat(finalSo.status())
                .as("SO must advance to FULFILLED or CLOSED after full delivery + invoice")
                .isIn(SalesOrderStatus.FULFILLED.name(),
                      SalesOrderStatus.PARTIALLY_INVOICED.name(),
                      SalesOrderStatus.CLOSED.name());
    }

    // =========================================================================
    // Bar 3 — DIRECT walk-in invoice STILL issues stock on finalise (regression guard)
    // =========================================================================

    @Test
    void directInvoice_issuesStock_onFinalise_unchanged() {
        // Receipt: 15 @ 400
        ProductDto product = stockableProduct("DirectWidget", "900");
        publishAndDispatchReceipt(product, new BigDecimal("15"), new BigDecimal("400"));

        BigDecimal avgCost = requireSoh(product.id()).getAvgCost();
        assertThat(avgCost).isEqualByComparingTo(new BigDecimal("400"));

        // Direct walk-in invoice — no delivery, no SO
        setCtx();
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));
        setCtx();
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(product.uid(), pcsUid, new BigDecimal("5"), null, null));
        setCtx();
        BigDecimal grossAmt = salesInvoiceService.getByUid(draft.uid()).grossTotalAmount();
        salesInvoiceService.addPayment(draft.uid(),
                new AddPaymentRequest(TenderType.CASH, grossAmt, "TZS", null));
        setCtx();
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());

        // Dispatch SALE.FINALISED — DIRECT invoice MUST issue stock (issuesStock=true)
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        // Stock deducted: 15 − 5 = 10
        StockOnHand soh = requireSoh(product.id());
        assertThat(soh.getQuantity())
                .as("DIRECT invoice must still deduct stock on finalise")
                .isEqualByComparingTo(new BigDecimal("10"));

        // COGS = 5 × 400 = 2000
        BigDecimal expectedCogs = new BigDecimal("5").multiply(avgCost)
                .setScale(4, RoundingMode.HALF_UP);
        assertThat(cogsBalance())
                .as("DIRECT invoice must still post COGS on finalise")
                .isEqualByComparingTo(expectedCogs);
    }

    // =========================================================================
    // Bar 4 — Guard: cannot deliver more than open qty (BR-SO-11)
    // =========================================================================

    @Test
    void overDeliver_rejected_brSo11() {
        ProductDto product = stockableProduct("GuardWidget", "700");
        publishAndDispatchReceipt(product, new BigDecimal("10"), new BigDecimal("350"));

        SalesOrderDto so = createAndConfirmOrder(product, new BigDecimal("5"));
        List<SalesOrderLineDto> soLines = salesOrderService.listLines(so.uid());
        String solUid = soLines.get(0).uid();

        setCtx();
        assertThatThrownBy(() ->
                deliveryService.create(new CreateDeliveryRequest(
                        so.uid(), LocalDate.now(), null,
                        List.of(new CreateDeliveryRequest.DeliveryLineRequest(
                                solUid, new BigDecimal("6")))))  // 6 > open qty 5
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("BR-SO-11");
    }

    // =========================================================================
    // Bar 5 — Guard: cannot invoice more than delivered (BR-SO-11)
    // Deliver 3 of 5 ordered; attempt to invoice full 5 → rejected because
    // createInvoiceFromDelivery only creates lines for qty_delivered_base − qty_invoiced_base.
    // After invoicing the delivered 3, calling createInvoiceFromDelivery again on the same
    // delivery must throw (no uninvoiced lines).
    // =========================================================================

    @Test
    void doubleInvoiceSameDelivery_rejected_noUninvoicedLines() {
        ProductDto product = stockableProduct("InvGuardWidget", "1000");
        publishAndDispatchReceipt(product, new BigDecimal("10"), new BigDecimal("450"));

        SalesOrderDto so = createAndConfirmOrder(product, new BigDecimal("5"));
        List<SalesOrderLineDto> soLines = salesOrderService.listLines(so.uid());
        String solUid = soLines.get(0).uid();

        setCtx();
        DeliveryDto delivery = deliveryService.create(new CreateDeliveryRequest(
                so.uid(), LocalDate.now(), null,
                List.of(new CreateDeliveryRequest.DeliveryLineRequest(solUid, new BigDecimal("3")))));

        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_CONFIRMED));

        // First invoice — bills the 3 delivered
        setCtx();
        SalesInvoiceDto inv1 = deliveryService.createInvoiceFromDelivery(delivery.uid());
        assertThat(inv1.status()).isEqualTo(InvoiceStatus.DRAFT);

        // Second call — all delivered qty already invoiced; must throw
        String deliveryUid = delivery.uid();
        setCtx();
        assertThatThrownBy(() -> deliveryService.createInvoiceFromDelivery(deliveryUid))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no uninvoiced lines");
    }

    // =========================================================================
    // Bar 6 — Discount on SO → invoice VAT computed on discounted net (BR-SO-10)
    // Order 5 × 1000 with 10% line discount → net = 4500; VAT 18% on 4500 = 810.
    // After delivery + createInvoiceFromDelivery the invoice net/vat must match.
    // =========================================================================

    @Test
    void discountFlowsFromSoToInvoice_vatOnDiscountedNet() {
        ProductDto product = stockableProduct("DiscSeamWidget", "1000");
        publishAndDispatchReceipt(product, new BigDecimal("10"), new BigDecimal("500"));

        // Create SO with 10% line discount
        setCtx();
        SalesOrderDto so = salesOrderService.create(new CreateSalesOrderRequest(
                company.getUid(), customerUid, agentUid, "TZS",
                LocalDate.now(), null, null, null, null));
        setCtx();
        salesOrderService.addLine(so.uid(), new AddSalesOrderLineRequest(
                product.uid(), pcsUid, new BigDecimal("5"),
                null, null, new BigDecimal("10")));   // 10% line discount

        setCtx();
        salesOrderService.confirm(so.uid());

        List<SalesOrderLineDto> soLines = salesOrderService.listLines(so.uid());
        String solUid = soLines.get(0).uid();

        // Deliver all 5
        setCtx();
        DeliveryDto delivery = deliveryService.create(new CreateDeliveryRequest(
                so.uid(), LocalDate.now(), null,
                List.of(new CreateDeliveryRequest.DeliveryLineRequest(solUid, new BigDecimal("5")))));

        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_CONFIRMED));

        // Create invoice from delivery
        setCtx();
        SalesInvoiceDto draftInv = deliveryService.createInvoiceFromDelivery(delivery.uid());

        // Net = 5 × 1000 × (1 − 0.10) = 4500; VAT 18% = 810; gross = 5310
        setCtx();
        SalesInvoiceDto inv = salesInvoiceService.getByUid(draftInv.uid());
        assertThat(inv.netTotalAmount())
                .as("invoice net must reflect 10% line discount: 5 × 1000 × 90% = 4500")
                .isEqualByComparingTo(new BigDecimal("4500.0000"));
        assertThat(inv.vatTotalAmount())
                .as("VAT must be on discounted net: 4500 × 18% = 810")
                .isEqualByComparingTo(new BigDecimal("810.0000"));
        assertThat(inv.grossTotalAmount())
                .as("gross = 4500 + 810 = 5310")
                .isEqualByComparingTo(new BigDecimal("5310.0000"));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void setCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "del_root", true, company.getId(), branch.getId(), null));
    }

    private ProductDto stockableProduct(String name, String price) {
        setCtx();
        ProductDto p = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null));
        productService.setPrice(p.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto(price, "TZS")));
        return p;
    }

    private SalesOrderDto createAndConfirmOrder(ProductDto product, BigDecimal qty) {
        setCtx();
        SalesOrderDto so = salesOrderService.create(new CreateSalesOrderRequest(
                company.getUid(), customerUid, agentUid, "TZS",
                LocalDate.now(), null, null, null, null));
        setCtx();
        salesOrderService.addLine(so.uid(), new AddSalesOrderLineRequest(
                product.uid(), pcsUid, qty, null, null, null));
        setCtx();
        salesOrderService.confirm(so.uid());
        return so;
    }

    private void publishAndDispatchReceipt(ProductDto product, BigDecimal qty, BigDecimal cost) {
        StockReceivedPayload payload = new StockReceivedPayload(
                // receipt ref → stock_movements.source_document_uid VARCHAR(26); a 26-char uid, NOT a 35-char "RCPT-DEL-"+uid.
                product.uid(), company.getId(), branch.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(
                        product.id(), product.uid(), null, qty, cost)));
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.STOCK_RECEIVED,
                    // aggregate_uid is VARCHAR(26) — use the 26-char product uid, NOT "RCPT-DEL-"+uid (35 chars).
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

    private BigDecimal safeBalance(Long accountId) {
        BigDecimal b = journalLines.accountBalance(company.getId(), accountId);
        return b != null ? b : BigDecimal.ZERO;
    }

    private BigDecimal cogsBalance() {
        return safeBalance(glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.COGS)
                .orElseThrow().getAccountId());
    }

    private BigDecimal inventoryBalance() {
        return safeBalance(glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.INVENTORY)
                .orElseThrow().getAccountId());
    }

    private BigDecimal revenueBalance() {
        return safeBalance(glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.SALES_REVENUE)
                .orElseThrow().getAccountId());
    }
}
