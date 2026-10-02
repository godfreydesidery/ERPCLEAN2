package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.parties.service.SupplierService;
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
import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.DirectGoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.DirectGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnedPayload;
import com.erp.modules.purchases.domain.dto.VoidGoodsReceiptRequest;
import com.erp.modules.purchases.service.DirectGoodsReceiptService;
import com.erp.modules.purchases.service.GoodsReceiptService;
import com.erp.modules.purchases.service.PurchaseOrderService;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockMovementRepository;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Voiding a goods receipt (or returning goods to the supplier) must take the receipt's own value
 * back out of stock — the live defect where a voided GRN left its value behind.
 *
 * <p>Live evidence (BR-02): receive 30 @ 2,200, void it, receive 50 @ 2,250, sell 20. The void's
 * GOODS_RECEIPT_REVERSAL row carried qty −30 but value <b>+66,000</b>, and whenever the product
 * had stock anywhere else in the company the voided receipt's value stayed on the branch row
 * (the reversal re-attributed {@code pre-void qty × avg} to the row whose quantity was about to
 * drop by 30). The next receipt then blended that phantom value into the average — RV-P01 sat at
 * 2,717.90 although nothing was ever bought above 2,250, and the sale was costed at 2,656.67.
 *
 * <p>The rule asserted here (ADR-0020 D-5, made exact): a void issues the receipt's quantity at the
 * receipt's OWN value (negative on the movement row); Σ on_hand_value falls by exactly that value;
 * the GL posts DR GRNI / CR Inventory at the same value; so Σ on_hand_value == GL 1300 after the
 * void. The average is re-derived from what is left (Σvalue / Σqty) and synced to every location
 * row. When nothing positive is left (qty ≤ 0) or the leftover value is negative, the last known
 * average is kept and the on-hand value carries the residual — the books still tie.
 *
 * <p>Every flow here is real: purchase order → goods receipt (or direct receipt) → void via the
 * purchases service, and each outbox event is dispatched synchronously.
 */
class GoodsReceiptVoidValuationIT extends PostgresIntegrationTest {

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

    @Autowired private SupplierService         supplierService;
    @Autowired private CustomerService         customerService;
    @Autowired private AgentService            agentService;
    @Autowired private ProductService          productService;
    @Autowired private PriceListService        priceListService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private TaxRateSeeder           taxRateSeeder;

    @Autowired private PurchaseOrderService      poService;
    @Autowired private GoodsReceiptService       grService;
    @Autowired private DirectGoodsReceiptService directService;
    @Autowired private SalesInvoiceService       salesInvoiceService;

    @Autowired private StockOnHandRepository   stockOnHandRepo;
    @Autowired private StockMovementRepository stockMovementRepo;

    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private TransactionTemplate     txTemplate;

    private Company company;
    private Branch  br01;
    private Branch  br02;
    private Long    rootId;
    private String  pcsUid;
    private String  priceListUid;
    private String  supplierUid;
    private String  customerUid;
    private String  agentUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("GrVoid IT Org"));
        company = companies.save(new Company(org, "GRVD", "GrVoid IT Co"));
        br01    = branches.save(new Branch(company, "BR-01", "Branch One"));
        br02    = branches.save(new Branch(company, "BR-02", "Branch Two"));

        AppUser root = new AppUser("grvoid_root", passwordEncoder.encode("GrV0id!Xx12"), "GrVoid Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();

        in(br01);
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

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "GrVoid Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null)).uid();
        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "GrVoid Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null)).uid();
        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "GrVoid Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // The live sequence, on a product held nowhere else
    // =========================================================================

    @Test
    void liveSequence_voidThenReceiveThenSell_costsAtTheSurvivingReceipt() {
        ProductDto p = product("RV-P01");

        in(br02);
        GoodsReceiptDto voided = receiveViaPo(p, "30", "2200");
        voidReceipt(voided);
        receiveViaPo(p, "50", "2250");
        sell(p, "20");

        StockMovement reversal = singleReversal(voided.uid());
        assertThat(reversal.getQuantity()).isEqualByComparingTo("-30");
        assertThat(reversal.getUnitCostAmount())
                .as("the void issues at the voided receipt's own unit cost")
                .isEqualByComparingTo("2200");
        assertThat(reversal.getValueAmount())
                .as("a reversal takes value OUT of stock — its value is negative (live: +66,000)")
                .isEqualByComparingTo("-66000");

        assertThat(companyAvg(p)).as("avg after the void + 50 @ 2,250").isEqualByComparingTo("2250");
        assertThat(cogsBalance()).as("sale COGS = 20 × 2,250").isEqualByComparingTo("45000");
        assertThat(onHandQty(p)).isEqualByComparingTo("30");
        assertThat(onHandValue(p)).as("30 left × 2,250").isEqualByComparingTo("67500");
        assertThat(onHandValue(p)).as("Σ on-hand value == GL 1300").isEqualByComparingTo(inventoryBalance());
        assertThat(grniBalance()).as("GRNI holds only the surviving, unbilled receipt")
                .isEqualByComparingTo("-112500");
    }

    // =========================================================================
    // The live sequence when the product ALSO sits at another branch — the case that inflated
    // the average on the live estate. The first receipt at BR-02 is a DIRECT receipt, so the
    // direct-receipt void path is exercised too.
    // =========================================================================

    @Test
    void liveSequence_withStockAtAnotherBranch_voidLeavesNoValueBehind() {
        ProductDto p = product("RV-P01-MULTI");

        in(br01);
        receiveViaPo(p, "100", "2250");               // BR-01: 100 @ 2,250 = 225,000

        in(br02);
        GoodsReceiptDto voided = receiveDirect(p, "30", "2200");
        voidReceipt(voided);

        // Straight after the void: exactly the pre-receipt state, and the books tie.
        assertThat(onHandQty(p)).isEqualByComparingTo("100");
        assertThat(onHandValue(p))
                .as("the void removes exactly the 66,000 the receipt added (live: it stayed behind)")
                .isEqualByComparingTo("225000");
        assertThat(onHandValue(p)).as("Σ on-hand value == GL 1300 after the void")
                .isEqualByComparingTo(inventoryBalance());
        assertThat(companyAvg(p)).isEqualByComparingTo("2250");
        assertThat(rowAt(p, br02).getOnHandValue())
                .as("the BR-02 row holds no stock, so it holds no value")
                .isEqualByComparingTo("0");
        assertThat(singleReversal(voided.uid()).getValueAmount()).isEqualByComparingTo("-66000");

        receiveViaPo(p, "50", "2250");
        sell(p, "20");

        assertThat(companyAvg(p))
                .as("nothing was ever bought above 2,250 — the average cannot exceed it (live: 2,717.90)")
                .isEqualByComparingTo("2250");
        assertThat(cogsBalance())
                .as("sale COGS = 20 × 2,250 (live: costed at 2,656.67)")
                .isEqualByComparingTo("45000");
        assertThat(onHandQty(p)).isEqualByComparingTo("130");
        assertThat(onHandValue(p)).isEqualByComparingTo("292500");
        assertThat(onHandValue(p)).as("Σ on-hand value == GL 1300").isEqualByComparingTo(inventoryBalance());
        for (StockOnHand row : stockOnHandRepo.findByCompanyIdAndProductId(company.getId(), p.id())) {
            assertThat(row.getAvgCost()).as("avg is synced on every location row").isEqualByComparingTo("2250");
            assertThat(row.getOnHandValue())
                    .as("each row's value is its own qty × avg")
                    .isEqualByComparingTo(row.getQuantity().multiply(new BigDecimal("2250")));
        }
    }

    // =========================================================================
    // Void after part of the receipt was already sold — the rule, stated:
    //   the void still takes out the receipt's FULL quantity at its FULL original value (the GL
    //   reverses GRNI for the whole receipt, so stock must match); COGS already posted for the units
    //   sold is NOT re-costed (moving average cannot un-post an issue).
    // =========================================================================

    @Test
    void voidAfterPartSold_onlyStock_goesNegativeAtTheReceiptCost_andStillTies() {
        ProductDto p = product("PART-SOLD-1");

        in(br02);
        GoodsReceiptDto voided = receiveViaPo(p, "30", "2200");
        sell(p, "20");                                  // COGS 44,000; 10 left worth 22,000
        voidReceipt(voided);

        assertThat(onHandQty(p)).as("30 issued back against 10 on hand").isEqualByComparingTo("-20");
        assertThat(onHandValue(p)).as("22,000 − 66,000").isEqualByComparingTo("-44000");
        assertThat(companyAvg(p))
                .as("nothing positive left to average — last known average kept, never negative")
                .isEqualByComparingTo("2200");
        assertThat(onHandValue(p)).as("Σ on-hand value == GL 1300").isEqualByComparingTo(inventoryBalance());
        assertThat(grniBalance()).as("GRNI fully reversed").isEqualByComparingTo("0");
        assertThat(cogsBalance()).as("COGS of the units sold stays posted").isEqualByComparingTo("44000");

        // The replacement receipt at the same cost puts everything back square.
        receiveViaPo(p, "30", "2200");
        assertThat(onHandQty(p)).isEqualByComparingTo("10");
        assertThat(onHandValue(p)).isEqualByComparingTo("22000");
        assertThat(onHandValue(p)).isEqualByComparingTo(inventoryBalance());
    }

    @Test
    void voidAfterPartSold_withOtherStock_reAveragesWhatIsLeft_totalCostConserved() {
        ProductDto p = product("PART-SOLD-2");

        in(br02);
        receiveViaPo(p, "100", "2000");                 // 200,000
        GoodsReceiptDto voided = receiveViaPo(p, "30", "2260"); // +67,800 → 130 @ 2,060
        assertThat(companyAvg(p)).isEqualByComparingTo("2060");
        sell(p, "20");                                  // COGS 41,200 at the blended 2,060
        voidReceipt(voided);

        // 80 left. Value = 267,800 − 41,200 − 67,800 = 158,800 → avg 1,985.
        assertThat(onHandQty(p)).isEqualByComparingTo("80");
        assertThat(onHandValue(p)).isEqualByComparingTo("158800");
        assertThat(companyAvg(p)).isEqualByComparingTo("1985");
        assertThat(onHandValue(p)).as("Σ on-hand value == GL 1300").isEqualByComparingTo(inventoryBalance());
        assertThat(cogsBalance()).isEqualByComparingTo("41200");
        // Had the receipt never existed: 80 @ 2,000 = 160,000 and COGS 40,000. The same 200,000
        // of cost is split 1,200 differently because those 20 units were costed while the voided
        // receipt was in the average — the stated, accepted moving-average limit.
        assertThat(onHandValue(p).add(cogsBalance())).isEqualByComparingTo("200000");
    }

    // =========================================================================
    // Purchase return: the same sign bug and the same value-left-behind bug
    // =========================================================================

    @Test
    void purchaseReturn_withStockAtAnotherBranch_takesOutTheReturnedValue_negativeOnTheMovement() {
        ProductDto p = product("PRET-1");

        in(br01);
        receiveViaPo(p, "100", "2250");                 // 225,000 at BR-01
        in(br02);
        GoodsReceiptDto gr = receiveViaPo(p, "30", "2250"); // +67,500 at BR-02

        String returnUid = "PRET-IT-" + System.nanoTime();
        PurchaseReturnedPayload payload = new PurchaseReturnedPayload(
                returnUid, company.getId(), br02.getId(), new BigDecimal("22500"), "TZS", false,
                List.of(new PurchaseReturnedPayload.ReturnLine(
                        gr.lines().get(0).id(),
                        gr.lines().get(0).uid(), p.id(),
                        new BigDecimal("10"), new BigDecimal("2250"), new BigDecimal("22500"))),
                "PR-IT-1");
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.PURCHASE_RETURNED,
                    DomainEventType.AGG_GOODS_RECEIPT, 1L, returnUid,
                    company.getId(), br02.getId(), payload);
            return null;
        });
        dispatcher.dispatchOne(pendingEvent(DomainEventType.PURCHASE_RETURNED));

        List<StockMovement> moves = stockMovementRepo
                .findBySourceDocumentUidAndMovementType(returnUid, MovementType.PURCHASE_RETURN);
        assertThat(moves).hasSize(1);
        assertThat(moves.get(0).getQuantity()).isEqualByComparingTo("-10");
        assertThat(moves.get(0).getValueAmount())
                .as("a return takes value out of stock — negative on the movement row")
                .isEqualByComparingTo("-22500");

        assertThat(onHandQty(p)).isEqualByComparingTo("120");
        assertThat(onHandValue(p)).isEqualByComparingTo("270000");
        assertThat(companyAvg(p)).isEqualByComparingTo("2250");
        assertThat(rowAt(p, br02).getOnHandValue()).isEqualByComparingTo("45000");
        assertThat(onHandValue(p)).as("Σ on-hand value == GL 1300").isEqualByComparingTo(inventoryBalance());
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private void in(Branch b) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "grvoid_root", true, company.getId(), b.getId(), null));
    }

    private ProductDto product(String name) {
        ProductDto p = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(p.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto("3000", "TZS")));
        return p;
    }

    private GoodsReceiptDto receiveViaPo(ProductDto p, String qty, String cost) {
        PurchaseOrderDto draft = poService.create(new CreatePurchaseOrderRequest(
                company.getUid(), supplierUid, "TZS", null, null,
                List.of(new AddPurchaseOrderLineRequest(
                        p.uid(), pcsUid, new BigDecimal(qty), new BigDecimal(cost), null))));
        PurchaseOrderDto placed = poService.placeOrder(draft.uid());
        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), "GrVoid IT receipt",
                List.of(new GoodsReceiptLineRequest(placed.lines().get(0).uid(), new BigDecimal(qty)))));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.STOCK_RECEIVED));
        return gr;
    }

    private GoodsReceiptDto receiveDirect(ProductDto p, String qty, String cost) {
        GoodsReceiptDto gr = directService.receiveDirect(new DirectGoodsReceiptRequest(
                company.getUid(), supplierUid, "TZS", "GrVoid IT direct receipt",
                List.of(new DirectGoodsReceiptLineRequest(
                        p.uid(), pcsUid, new BigDecimal(qty), new BigDecimal(cost), null))));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.STOCK_RECEIVED));
        return gr;
    }

    private void voidReceipt(GoodsReceiptDto gr) {
        grService.voidReceipt(gr.uid(), new VoidGoodsReceiptRequest("received in error"));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.STOCK_RECEIPT_VOIDED));
    }

    private void sell(ProductDto p, String qty) {
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), customerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(p.uid(), pcsUid, new BigDecimal(qty), null, null));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));
    }

    private StockMovement singleReversal(String receiptUid) {
        List<StockMovement> rows = stockMovementRepo
                .findBySourceDocumentUidAndMovementType(receiptUid, MovementType.GOODS_RECEIPT_REVERSAL);
        assertThat(rows).as("one reversal row for the voided receipt").hasSize(1);
        return rows.get(0);
    }

    private Long pendingEvent(String eventType) {
        return domainEventRepo.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .reduce((a, b) -> b)
                .map(DomainEvent::getId)
                .orElseThrow(() -> new AssertionError("No PENDING event of type: " + eventType));
    }

    private List<StockOnHand> rows(ProductDto p) {
        return stockOnHandRepo.findByCompanyIdAndProductId(company.getId(), p.id());
    }

    private StockOnHand rowAt(ProductDto p, Branch b) {
        List<StockOnHand> atBranch = rows(p).stream()
                .filter(r -> r.getBranchId().equals(b.getId())).toList();
        assertThat(atBranch).as("one on-hand row at " + b.getCode()).hasSize(1);
        return atBranch.get(0);
    }

    private BigDecimal onHandQty(ProductDto p) {
        return rows(p).stream().map(StockOnHand::getQuantity).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal onHandValue(ProductDto p) {
        return rows(p).stream().map(StockOnHand::getOnHandValue).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private BigDecimal companyAvg(ProductDto p) {
        List<BigDecimal> avgs = rows(p).stream().map(StockOnHand::getAvgCost)
                .filter(a -> a != null).distinct().toList();
        assertThat(avgs.stream().map(BigDecimal::stripTrailingZeros).distinct().toList())
                .as("avg_cost is one company-wide figure, synced on every row").hasSize(1);
        return avgs.get(0);
    }

    private BigDecimal balance(GlConfigKey key) {
        Long accountId = glConfigRepo.findByCompanyIdAndConfigKey(company.getId(), key)
                .orElseThrow().getAccountId();
        BigDecimal b = journalLines.accountBalance(company.getId(), accountId);
        return b != null ? b : BigDecimal.ZERO;
    }

    private BigDecimal inventoryBalance() { return balance(GlConfigKey.INVENTORY); }
    private BigDecimal grniBalance()      { return balance(GlConfigKey.GRNI); }
    private BigDecimal cogsBalance()      { return balance(GlConfigKey.COGS); }
}
