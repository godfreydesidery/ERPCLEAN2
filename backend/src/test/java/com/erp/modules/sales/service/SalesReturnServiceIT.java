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
import com.erp.modules.products.domain.dto.AddComponentRequest;
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
import com.erp.modules.sales.domain.dto.CreateSalesOrderRequest;
import com.erp.modules.sales.domain.dto.CreateSalesReturnRequest;
import com.erp.modules.sales.domain.dto.DeliveryDto;
import com.erp.modules.sales.domain.dto.SalesOrderDto;
import com.erp.modules.sales.domain.dto.SalesOrderLineDto;
import com.erp.modules.sales.domain.dto.SalesReturnDto;
import com.erp.modules.sales.repository.DeliveryLineRepository;
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
 * Stage-2 O2C integration tests: Sales Returns / RMA (ADR-0021 D-11).
 *
 * <p>Bars covered:
 * <ol>
 *   <li>Full return: stock UP + DR Inventory(1300) / CR COGS(5100) at ORIGINAL issued cost
 *       + credit note raised with origin=RETURN.</li>
 *   <li>Over-return guard: qty &gt; (delivered - already returned) rejected (BR-SO-11).</li>
 *   <li>Partial return; second partial up to balance; then over-return rejected.</li>
 *   <li>Idempotency: re-dispatching DELIVERY.RETURNED does not double-restock.</li>
 *   <li>Return at ORIGINAL cost: avg changes after delivery; reversal still uses delivery cost.</li>
 * </ol>
 */
class SalesReturnServiceIT extends PostgresIntegrationTest {

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
    @Autowired private SalesReturnService      salesReturnService;

    @Autowired private DeliveryLineRepository  deliveryLineRepo;
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

        Organisation org = organisations.save(new Organisation("Ret IT Org"));
        company = companies.save(new Company(org, "RETI", "Ret IT Co"));
        branch  = branches.save(new Branch(company, "RETI1", "Ret IT Branch"));

        AppUser root = new AppUser("ret_root", passwordEncoder.encode("R3tRoot!Xx"), "Ret Root");
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
                company.getId(), PartyType.INDIVIDUAL, "Ret IT Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "Ret IT Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // Bar 1 -- Full return: stock UP + DR Inventory / CR COGS at original cost + credit note
    // =========================================================================

    @Test
    void fullReturn_stockUp_cogsReversed_atOriginalCost_creditNoteRaised() {
        // Receipt 10 @ 800 -> avg_cost = 800
        ProductDto product = stockableProduct("RetWidget", "1500");
        publishAndDispatchReceipt(product, new BigDecimal("10"), new BigDecimal("800"));

        BigDecimal avgCostAtDelivery = requireSoh(product.id()).getAvgCost();
        assertThat(avgCostAtDelivery).isEqualByComparingTo("800");

        // Confirm SO for 5 and deliver all 5
        SalesOrderDto so = createAndConfirmOrder(product, new BigDecimal("5"));
        DeliveryDto delivery = deliverAll(so, new BigDecimal("5"));

        // Dispatch DELIVERY.CONFIRMED -> issues stock, posts COGS (5 x 800 = 4000)
        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_CONFIRMED));

        assertThat(requireSoh(product.id()).getQuantity())
                .isEqualByComparingTo("5");  // 10 - 5

        BigDecimal expectedCogs = new BigDecimal("5").multiply(avgCostAtDelivery)
                .setScale(4, RoundingMode.HALF_UP);
        assertThat(cogsBalance()).isEqualByComparingTo(expectedCogs);

        // Capture inventory balance before return
        BigDecimal invBalBeforeReturn = inventoryBalance();

        // Create the full return (all 5)
        setCtx();
        SalesReturnDto ret = salesReturnService.create(new CreateSalesReturnRequest(
                delivery.uid(), LocalDate.now(), "Customer returned all",
                List.of(new CreateSalesReturnRequest.ReturnLineRequest(
                        delivery.lines().get(0).uid(), new BigDecimal("5")))));

        assertThat(ret.returnNumber()).startsWith("RET-");
        assertThat(ret.status()).isEqualTo("CONFIRMED");
        assertThat(ret.creditNoteUid()).isNotNull().isNotBlank();

        // Dispatch DELIVERY.RETURNED -> stock back IN + COGS reversal GL
        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_RETURNED));

        // Stock qty: 5 + 5 = 10 (fully back)
        assertThat(requireSoh(product.id()).getQuantity())
                .as("stock qty must go back UP by returned qty")
                .isEqualByComparingTo("10");

        // GL: DR Inventory at original issued cost (5 x 800 = 4000)
        assertThat(inventoryBalance())
                .as("Inventory GL: DR Inventory at original issued cost on return")
                .isEqualByComparingTo(invBalBeforeReturn.add(expectedCogs));

        // COGS net after return = 4000 - 4000 = 0 (full reversal)
        assertThat(cogsBalance())
                .as("COGS GL: CR COGS fully reversed at original cost")
                .isEqualByComparingTo(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));
    }

    // =========================================================================
    // Bar 2 -- Over-return guard (BR-SO-11)
    // =========================================================================

    @Test
    void overReturn_rejected_brSo11() {
        ProductDto product = stockableProduct("OvRetWidget", "900");
        publishAndDispatchReceipt(product, new BigDecimal("10"), new BigDecimal("500"));

        SalesOrderDto so = createAndConfirmOrder(product, new BigDecimal("4"));
        DeliveryDto delivery = deliverAll(so, new BigDecimal("4"));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_CONFIRMED));

        String dlUid = delivery.lines().get(0).uid();

        setCtx();
        assertThatThrownBy(() ->
                salesReturnService.create(new CreateSalesReturnRequest(
                        delivery.uid(), LocalDate.now(), "too many",
                        List.of(new CreateSalesReturnRequest.ReturnLineRequest(
                                dlUid, new BigDecimal("5")))))   // 5 > 4 delivered
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("BR-SO-11");
    }

    // =========================================================================
    // Bar 3 -- Partial return; second partial up to balance; third over-return rejected
    // =========================================================================

    @Test
    void partialReturns_upToBalance_thenOverReturnRejected() {
        ProductDto product = stockableProduct("PartRetWidget", "1200");
        publishAndDispatchReceipt(product, new BigDecimal("10"), new BigDecimal("600"));

        SalesOrderDto so = createAndConfirmOrder(product, new BigDecimal("6"));
        DeliveryDto delivery = deliverAll(so, new BigDecimal("6"));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_CONFIRMED));

        String dlUid = delivery.lines().get(0).uid();

        // First partial return: 2 of 6
        setCtx();
        SalesReturnDto ret1 = salesReturnService.create(new CreateSalesReturnRequest(
                delivery.uid(), LocalDate.now(), "First partial",
                List.of(new CreateSalesReturnRequest.ReturnLineRequest(dlUid, new BigDecimal("2")))));
        assertThat(ret1.returnNumber()).startsWith("RET-");

        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_RETURNED));

        // Stock: 10 - 6 + 2 = 6
        assertThat(requireSoh(product.id()).getQuantity()).isEqualByComparingTo("6");

        // Second partial return: 3 of remaining 4
        setCtx();
        SalesReturnDto ret2 = salesReturnService.create(new CreateSalesReturnRequest(
                delivery.uid(), LocalDate.now(), "Second partial",
                List.of(new CreateSalesReturnRequest.ReturnLineRequest(dlUid, new BigDecimal("3")))));
        assertThat(ret2.returnNumber()).startsWith("RET-");
        assertThat(ret2.returnNumber()).isNotEqualTo(ret1.returnNumber());

        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_RETURNED));

        // Stock: 6 + 3 = 9
        assertThat(requireSoh(product.id()).getQuantity()).isEqualByComparingTo("9");

        // Third attempt: 2 but only 1 remains -> rejected
        setCtx();
        assertThatThrownBy(() ->
                salesReturnService.create(new CreateSalesReturnRequest(
                        delivery.uid(), LocalDate.now(), "Over limit",
                        List.of(new CreateSalesReturnRequest.ReturnLineRequest(
                                dlUid, new BigDecimal("2")))))   // only 1 returnable left
        ).isInstanceOf(IllegalStateException.class)
         .hasMessageContaining("BR-SO-11");
    }

    // =========================================================================
    // Bar 4 -- Idempotency: re-dispatching DELIVERY.RETURNED does not double-restock
    // =========================================================================

    @Test
    void idempotentReturn_reDispatchDoesNotDoubleRestock() {
        ProductDto product = stockableProduct("IdemRetWidget", "1000");
        publishAndDispatchReceipt(product, new BigDecimal("10"), new BigDecimal("700"));

        SalesOrderDto so = createAndConfirmOrder(product, new BigDecimal("3"));
        DeliveryDto delivery = deliverAll(so, new BigDecimal("3"));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_CONFIRMED));

        String dlUid = delivery.lines().get(0).uid();

        setCtx();
        salesReturnService.create(new CreateSalesReturnRequest(
                delivery.uid(), LocalDate.now(), "Idem test",
                List.of(new CreateSalesReturnRequest.ReturnLineRequest(
                        dlUid, new BigDecimal("3")))));

        // First dispatch -- stock goes from 7 to 10
        Long eventId = pendingEvent(DomainEventType.DELIVERY_RETURNED);
        dispatcher.dispatchOne(eventId);

        BigDecimal qtyAfterFirst = requireSoh(product.id()).getQuantity();
        assertThat(qtyAfterFirst).isEqualByComparingTo("10");

        // Re-dispatch the SAME event -- IdempotencyGuard must block double-restock
        dispatcher.dispatchOne(eventId);

        assertThat(requireSoh(product.id()).getQuantity())
                .as("Idempotency: re-dispatch of DELIVERY.RETURNED must not add stock again")
                .isEqualByComparingTo(qtyAfterFirst);
    }

    // =========================================================================
    // Bar 5 -- Return at ORIGINAL cost: avg changes after delivery; reversal uses delivery cost
    // =========================================================================

    @Test
    void returnUsesOriginalIssuedCost_notCurrentAvg() {
        // Receipt 5 @ 400 -> avg = 400
        ProductDto product = stockableProduct("OrigCostWidget", "800");
        publishAndDispatchReceipt(product, new BigDecimal("5"), new BigDecimal("400"));

        SalesOrderDto so = createAndConfirmOrder(product, new BigDecimal("2"));
        DeliveryDto delivery = deliverAll(so, new BigDecimal("2"));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_CONFIRMED));

        // COGS posted at delivery: 2 x 400 = 800
        assertThat(cogsBalance()).isEqualByComparingTo("800.0000");

        // Receive more stock at DIFFERENT cost: 3 on-hand @ 400, add 5 @ 600
        // new avg = (3x400 + 5x600) / (3+5) = 4200/8 = 525
        publishAndDispatchReceipt(product, new BigDecimal("5"), new BigDecimal("600"));
        BigDecimal newAvg = requireSoh(product.id()).getAvgCost();
        assertThat(newAvg).isGreaterThan(new BigDecimal("400"));

        // Capture inventory balance before return
        BigDecimal invBeforeReturn = inventoryBalance();

        // Return 1 unit from the delivery (pro-rated original issued cost = 1/2 x 800 = 400)
        String dlUid = delivery.lines().get(0).uid();
        setCtx();
        salesReturnService.create(new CreateSalesReturnRequest(
                delivery.uid(), LocalDate.now(), "Original cost test",
                List.of(new CreateSalesReturnRequest.ReturnLineRequest(
                        dlUid, new BigDecimal("1")))));

        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_RETURNED));

        // COGS reversal must be at ORIGINAL cost (400), NOT current avg (525)
        // cogsBalance = 800 - 400 = 400
        assertThat(cogsBalance())
                .as("COGS reversal must use original issued cost (400), not current avg")
                .isEqualByComparingTo("400.0000");

        // Inventory DR = 400 (original cost), not 525
        assertThat(inventoryBalance())
                .as("Inventory GL must increase by original issued cost (400)")
                .isEqualByComparingTo(invBeforeReturn.add(new BigDecimal("400.0000")));
    }

    // =========================================================================
    // Bar 6 — FIX-1: composed/recipe product delivery writes issue_value_amount;
    //                 return posts DR Inventory / CR COGS at the ORIGINAL issued cost.
    // =========================================================================

    @Test
    void composedProduct_deliveryWritesIssueValue_returnReversesCogsAtOriginalCost() {
        // Two stockable components: comp1 @ 100, comp2 @ 200. Composed = 2×comp1 + 1×comp2.
        // Deliver 1 composed unit → issued value = 2×100 + 1×200 = 400.
        ProductDto comp1 = stockableProduct("KitComp1", "50");   // sell price irrelevant
        ProductDto comp2 = stockableProduct("KitComp2", "50");
        ProductDto kit   = stockableProduct("Kit",      "999");  // sell price irrelevant

        setCtx();
        productService.addComponent(kit.uid(), new AddComponentRequest(comp1.uid(), new BigDecimal("2")));
        setCtx();
        productService.addComponent(kit.uid(), new AddComponentRequest(comp2.uid(), new BigDecimal("1")));

        // Stock up both components at known costs
        publishAndDispatchReceipt(comp1, new BigDecimal("10"), new BigDecimal("100")); // avg=100
        publishAndDispatchReceipt(comp2, new BigDecimal("10"), new BigDecimal("200")); // avg=200

        // Create SO for 1 kit and deliver
        SalesOrderDto so = createAndConfirmOrder(kit, BigDecimal.ONE);
        DeliveryDto delivery = deliverAll(so, BigDecimal.ONE);

        // Dispatch DELIVERY.CONFIRMED — DeliveryIssueStockHandler must now write
        // issue_value_amount = 2×100 + 1×200 = 400 back to the delivery_line (FIX-1).
        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_CONFIRMED));

        // Assert delivery_line.issue_value_amount is non-null and = 400
        String dlUid = delivery.lines().get(0).uid();
        var dlRows = deliveryLineRepo.findByDeliveryIdOrderByLineNo(delivery.id());
        assertThat(dlRows).hasSize(1);
        assertThat(dlRows.get(0).getIssueValueAmount())
                .as("delivery_line.issue_value_amount must be populated for composed product (FIX-1)")
                .isNotNull()
                .isEqualByComparingTo("400.0000");

        BigDecimal expectedCogs = new BigDecimal("400.0000");
        assertThat(cogsBalance())
                .as("COGS GL must be 400 after composed-product delivery")
                .isEqualByComparingTo(expectedCogs);

        BigDecimal invBeforeReturn = inventoryBalance();

        // Return the kit — SalesReturnServiceImpl reads issue_value_amount=400 and
        // passes it as originalIssueValue so DeliveryReturnStockHandler reverses at 400.
        setCtx();
        SalesReturnDto ret = salesReturnService.create(new CreateSalesReturnRequest(
                delivery.uid(), LocalDate.now(), "Kit return test",
                List.of(new CreateSalesReturnRequest.ReturnLineRequest(dlUid, BigDecimal.ONE))));

        assertThat(ret.returnNumber()).startsWith("RET-");
        assertThat(ret.creditNoteUid()).isNotNull().isNotBlank();

        // Dispatch DELIVERY.RETURNED
        dispatcher.dispatchOne(pendingEvent(DomainEventType.DELIVERY_RETURNED));

        // COGS fully reversed: 400 - 400 = 0
        assertThat(cogsBalance())
                .as("COGS must be fully reversed at the original issued cost (400) — not at current avg")
                .isEqualByComparingTo(BigDecimal.ZERO.setScale(4, RoundingMode.HALF_UP));

        // Inventory DR = 400
        assertThat(inventoryBalance())
                .as("Inventory GL must increase by the original issued cost (400)")
                .isEqualByComparingTo(invBeforeReturn.add(new BigDecimal("400.0000")));
    }

    // =========================================================================
    // Bar 7 — FIX-3: negative discount and >100% percent are rejected at service layer
    // =========================================================================

    @Test
    @SuppressWarnings("java:S5778") // single-throw guard: DiscountValidator throws before any side effects
    void addLine_negativeDiscount_rejected() {
        setCtx();
        SalesOrderDto so = salesOrderService.create(new CreateSalesOrderRequest(
                company.getUid(), customerUid, agentUid, "TZS",
                LocalDate.now(), null, null, null, null));

        ProductDto product = stockableProduct("DiscTestProd", "1000");

        // Negative line discount amount must be rejected
        setCtx();
        AddSalesOrderLineRequest negAmtReq = new AddSalesOrderLineRequest(
                product.uid(), pcsUid, BigDecimal.ONE, null, new BigDecimal("-100"), null);
        assertThatThrownBy(() -> salesOrderService.addLine(so.uid(), negAmtReq))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be negative");

        // Discount percent > 100 must be rejected
        setCtx();
        AddSalesOrderLineRequest overPctReq = new AddSalesOrderLineRequest(
                product.uid(), pcsUid, BigDecimal.ONE, null, null, new BigDecimal("101"));
        assertThatThrownBy(() -> salesOrderService.addLine(so.uid(), overPctReq))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot exceed 100");
    }

    // =========================================================================
    // Private helpers (mirror DeliveryServiceIT)
    // =========================================================================

    private void setCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "ret_root", true, company.getId(), branch.getId(), null));
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

    private DeliveryDto deliverAll(SalesOrderDto so, BigDecimal qty) {
        setCtx();
        List<SalesOrderLineDto> soLines = salesOrderService.listLines(so.uid());
        String solUid = soLines.get(0).uid();
        setCtx();
        return deliveryService.create(new CreateDeliveryRequest(
                so.uid(), LocalDate.now(), null,
                List.of(new CreateDeliveryRequest.DeliveryLineRequest(solUid, qty))));
    }

    private void publishAndDispatchReceipt(ProductDto product, BigDecimal qty, BigDecimal cost) {
        StockReceivedPayload payload = new StockReceivedPayload(
                product.uid(), company.getId(), branch.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(
                        product.id(), product.uid(), null, qty, cost)));
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.STOCK_RECEIVED,
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
}
