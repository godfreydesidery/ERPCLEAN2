package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.ap.service.BillMatchService;
import com.erp.modules.ap.service.SupplierBillService;
import com.erp.modules.ap.domain.dto.BillLineRequest;
import com.erp.modules.ap.domain.dto.BillMatchResultDto;
import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
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
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.modules.stock.domain.dto.AdjustStockRequest;
import com.erp.modules.stock.domain.dto.OpeningBalanceRequest;
import com.erp.modules.stock.domain.dto.SetOpeningValuationRequest;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.dto.StockReceiptVoidedPayload;
import com.erp.modules.stock.domain.dto.StockValuationReportDto;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.AdjustmentReason;
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
 * Integration tests for inventory valuation and COGS (ADR-0020, FR-INV/BR-INV).
 *
 * <p>Acceptance bars covered:
 * <ol>
 *   <li>BR-INV-01 (a): first goods receipt sets avg_cost = receipt cost; posts DR Inventory/CR GRNI.</li>
 *   <li>BR-INV-01 (b): 2nd receipt at different cost → weighted average ((oldVal+qty×cost)/newQty, 4dp HALF_UP).</li>
 *   <li>BR-INV-01 edge: zero-cost receipt accepted — does not blow up, avg drags to zero.</li>
 *   <li>ADR-0020 D-9 / GRNI-clear: goods bill-match → DR GRNI/CR AP; GRNI nets to zero.</li>
 *   <li>Mixed bill: goods lines → DR GRNI; service lines → DR PURCHASES; balanced.</li>
 *   <li>BR-INV-03: sale → DR COGS/CR Inventory at qty × avg; on_hand_value tracks.</li>
 *   <li>BR-INV-03 BOM: composed product → one COGS leg per stockable component at its own avg.</li>
 *   <li>BR-INV-03 edge: null avg_cost → COGS leg skipped, qty still deducts (no crash).</li>
 *   <li>BR-INV-05 (sale void): DR Inventory/CR COGS at original cost; avg_cost unchanged.</li>
 *   <li>BR-INV-05 (receipt void): DR GRNI/CR Inventory at original cost; avg re-derived.</li>
 *   <li>BR-INV-07: setOpeningValue sets avg_cost + posts DR Inventory/CR OBE; once-per-product guard.</li>
 *   <li>BR-INV-06 (headline recon): report's Σ on_hand_value == GL 1300 balance (ties == true).</li>
 *   <li>BR-INV-09/D-7: adjustment decrease → DR STOCK_ADJUSTMENT/CR Inventory at avg.</li>
 *   <li>Idempotency: re-dispatching the same STOCK.RECEIVED event does NOT double-count.</li>
 * </ol>
 *
 * <p>Dispatch is synchronous via {@link DomainEventDispatcher#dispatchOne} — no sleeps.
 * Account IDs are resolved from {@link GlConfigRepository} directly (no MANDATORY-TX constraint).
 */
class InventoryValuationServiceIT extends PostgresIntegrationTest {

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
    @Autowired private GlConfigRepository      glConfigRepo;
    @Autowired private JournalLineRepository   journalLines;

    // ---- AP ----
    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private SupplierBillService     billService;
    @Autowired private BillMatchService        matchService;
    @Autowired private SupplierService         supplierService;

    // ---- Stock ----
    @Autowired private InventoryGlSeeder       invGlSeeder;
    @Autowired private InventoryValuationService inventoryValuationService;
    @Autowired private StockValuationQuery     valuationQuery;
    @Autowired private StockService            stockService;
    @Autowired private StockOnHandRepository   stockOnHandRepo;

    // ---- Products / Parties / Sales ----
    @Autowired private ProductService          productService;
    @Autowired private PriceListService        priceListService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private CustomerService         customerService;
    @Autowired private AgentService            agentService;
    @Autowired private SalesInvoiceService     salesInvoiceService;
    @Autowired private TaxRateSeeder           taxRateSeeder;

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
    private String  supplierUid;
    private String  customerUid;
    private String  agentUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("InvVal IT Org"));
        company = companies.save(new Company(org, "INVV", "InvVal IT Co"));
        branch  = branches.save(new Branch(company, "INVV1", "InvVal IT Branch"));

        AppUser root = new AppUser("invval_root", passwordEncoder.encode("InvV@l1!Xx"), "InvVal Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        setCtx();

        // GL foundations
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());      // 5150 PURCHASES + 3100 OBE
        invGlSeeder.seedDefaults(company.getId());     // 2150 GRNI + 5160 STOCK_ADJUSTMENT

        // Products / Sales foundations
        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();

        priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        // Party fixtures
        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "InvVal Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS)).uid();

        customerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "InvVal Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null)).uid();

        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "InvVal Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // BR-INV-01 (a): first goods receipt sets avg_cost = receipt cost
    //               + posts DR Inventory (1300) / CR GRNI (2150) at qty × cost
    // =========================================================================

    @Test
    void firstReceipt_setsAvgCostAndPostsInventoryGrniGl() {
        ProductDto product = stockableProduct("Widget-A");
        BigDecimal qty  = new BigDecimal("10");
        BigDecimal cost = new BigDecimal("500.00");

        Long eventId = publishReceiptEvent("RCPT-INV-001", product, qty, cost, 1L);
        dispatcher.dispatchOne(eventId);

        // avg_cost = receipt cost; on_hand_value = qty × cost
        StockOnHand soh = requireSoh(product.id());
        assertThat(soh.getAvgCost())
                .as("first receipt: avg_cost must equal receipt unit cost")
                .isEqualByComparingTo(cost);
        assertThat(soh.getOnHandValue())
                .as("on_hand_value = qty × cost = 5000")
                .isEqualByComparingTo(qty.multiply(cost).setScale(4, RoundingMode.HALF_UP));

        // GL: DR Inventory (1300) / CR GRNI (2150) at 5000
        BigDecimal expectedValue = new BigDecimal("5000");
        assertThat(inventoryBalance())
                .as("Inventory GL DR balance must equal receipt value")
                .isEqualByComparingTo(expectedValue);
        // GRNI is a CR-normal liability: net balance = DR - CR = 0 - 5000 = -5000
        assertThat(grniBalance().negate())
                .as("GRNI GL CR balance must equal receipt value")
                .isEqualByComparingTo(expectedValue);
    }

    // =========================================================================
    // BR-INV-01 (b): second receipt at different cost → weighted average
    // =========================================================================

    @Test
    void secondReceiptAtDifferentCost_computesWeightedAverage() {
        ProductDto product = stockableProduct("Widget-B");

        // Receipt 1: 10 @ 500 → value = 5000, avg = 500
        dispatcher.dispatchOne(publishReceiptEvent("RCPT-WA-001", product,
                new BigDecimal("10"), new BigDecimal("500"), 1L));

        // Receipt 2: 5 @ 800 → new_value = 5000 + 4000 = 9000; new_avg = 9000/15
        dispatcher.dispatchOne(publishReceiptEvent("RCPT-WA-002", product,
                new BigDecimal("5"), new BigDecimal("800"), 2L));

        BigDecimal expectedAvg = new BigDecimal("9000")
                .divide(new BigDecimal("15"), 4, RoundingMode.HALF_UP); // 600.0000

        StockOnHand soh = requireSoh(product.id());
        assertThat(soh.getAvgCost())
                .as("weighted-average after two receipts")
                .isEqualByComparingTo(expectedAvg);
        assertThat(soh.getOnHandValue())
                .as("on_hand_value = 5000 + 4000 = 9000")
                .isEqualByComparingTo(new BigDecimal("9000").setScale(4, RoundingMode.HALF_UP));
        assertThat(soh.getQuantity())
                .isEqualByComparingTo(new BigDecimal("15"));
    }

    // =========================================================================
    // BR-INV-01 edge: zero-cost receipt accepted — does not blow up
    // =========================================================================

    @Test
    void zeroCostReceipt_acceptedWithoutException_avgDragsToZero() {
        ProductDto product = stockableProduct("ZeroCost-Widget");

        Long ev = publishReceiptEvent("RCPT-ZERO-001", product,
                new BigDecimal("5"), BigDecimal.ZERO, 1L);

        // Must not throw
        dispatcher.dispatchOne(ev);

        StockOnHand soh = requireSoh(product.id());
        assertThat(soh.getQuantity()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(soh.getAvgCost()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // ADR-0020 D-9: AP bill-match GRNI clear
    // goods bill → DR GRNI / CR AP (not DR PURCHASES)
    // =========================================================================

    @Test
    void billMatchOnGoodsBill_clearGrni_drGrniCrAp() {
        ProductDto product = stockableProduct("GrniWidget");
        BigDecimal qty  = new BigDecimal("10");
        BigDecimal cost = new BigDecimal("1000.00");

        // 1. Receipt → DR Inventory / CR GRNI 10 000
        dispatcher.dispatchOne(publishReceiptEvent("RCPT-BILL-001", product, qty, cost, 1L));
        assertThat(grniBalance()).isEqualByComparingTo(new BigDecimal("-10000").setScale(4, RoundingMode.HALF_UP));

        // 2. Enter a bill where the line references a GR line (goods line)
        String grLineUid = "GR-LINE-FAKE-001";
        SupplierBillDto bill = billService.enterBill(new EnterBillRequest(
                company.getUid(), supplierUid, "INV-GRNI-001",
                null, LocalDate.now(), LocalDate.now().plusDays(30),
                BigDecimal.ZERO, "TZS", null,
                List.of(new BillLineRequest(null, null, grLineUid, "Widget goods",
                        qty, cost))));

        // 3. Match → must DR GRNI / CR AP (ADR-0020 D-9)
        BillMatchResultDto result = matchService.runMatch(bill.uid());
        assertThat(result.billStatus()).isEqualTo(SupplierBillStatus.MATCHED);

        // GRNI nets to zero: receipt CR 10 000 + bill-match DR 10 000 = 0
        assertThat(grniBalance())
                .as("GRNI must net to zero after goods bill is matched (ADR-0020 D-9)")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // AP: credited full amount
        assertThat(apBalance().negate())
                .as("AP control must be credited the bill amount")
                .isEqualByComparingTo(cost.multiply(qty));
    }

    // =========================================================================
    // Mixed bill: goods lines → GRNI; service lines → PURCHASES; balanced
    // =========================================================================

    @Test
    void billMatchMixedBill_goodsLinesGrni_serviceLinesUsePurchases_balanced() {
        ProductDto goodsProd = stockableProduct("MixedGoods");
        BigDecimal goodsQty  = new BigDecimal("5");
        BigDecimal goodsCost = new BigDecimal("2000.00");

        // Receive goods first
        dispatcher.dispatchOne(publishReceiptEvent("RCPT-MIXED-001", goodsProd, goodsQty, goodsCost, 1L));

        BigDecimal serviceAmt = new BigDecimal("3000.00");
        String grLineUid = "GR-LINE-MIXED-001";

        SupplierBillDto bill = billService.enterBill(new EnterBillRequest(
                company.getUid(), supplierUid, "INV-MIXED-001",
                null, LocalDate.now(), LocalDate.now().plusDays(30),
                BigDecimal.ZERO, "TZS", null,
                List.of(
                        // goods line — grLineUid set
                        new BillLineRequest(null, null, grLineUid, "Goods Widget",
                                goodsQty, goodsCost),
                        // service line — no grLineUid
                        new BillLineRequest(null, null, null, "Consulting",
                                BigDecimal.ONE, serviceAmt))));

        matchService.runMatch(bill.uid());

        // GRNI: receipt CR 10 000 + match DR 10 000 = 0
        assertThat(grniBalance())
                .as("GRNI must be zero after goods portion matched")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // PURCHASES: DR 3000 from service line
        assertThat(purchasesBalance())
                .as("PURCHASES account debited for service line")
                .isEqualByComparingTo(serviceAmt);

        // AP: CR = goods + service = 10 000 + 3000 = 13 000
        BigDecimal totalBill = goodsQty.multiply(goodsCost).add(serviceAmt);
        assertThat(apBalance().negate())
                .as("AP control credits the full bill amount")
                .isEqualByComparingTo(totalBill);
    }

    // =========================================================================
    // BR-INV-03: sale → DR COGS (5100) / CR Inventory (1300) at qty × avg
    // =========================================================================

    @Test
    void saleIssue_debitsCogs_creditsInventory_atAvgCost() {
        ProductDto product = stockableProduct("CogsWidget");
        BigDecimal receiptQty  = new BigDecimal("20");
        BigDecimal receiptCost = new BigDecimal("600.00");

        dispatcher.dispatchOne(publishReceiptEvent("RCPT-COGS-001", product, receiptQty, receiptCost, 1L));

        BigDecimal avg = requireSoh(product.id()).getAvgCost(); // 600

        // Sell 5
        BigDecimal sellQty = new BigDecimal("5");
        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), sellQty);
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        BigDecimal expectedCogs = sellQty.multiply(avg).setScale(4, RoundingMode.HALF_UP); // 3000

        assertThat(cogsBalance())
                .as("COGS DR balance must equal qty × avg_cost")
                .isEqualByComparingTo(expectedCogs);

        // Inventory net: receipt DR 12000 − COGS CR 3000 = 9000
        BigDecimal expectedInvNet = receiptQty.multiply(receiptCost)
                .subtract(expectedCogs)
                .setScale(4, RoundingMode.HALF_UP);
        assertThat(inventoryBalance())
                .as("Inventory GL net balance after receipt and issue")
                .isEqualByComparingTo(expectedInvNet);

        // on_hand_value must mirror the GL balance
        assertThat(requireSoh(product.id()).getOnHandValue())
                .as("on_hand_value must equal Inventory GL net balance")
                .isEqualByComparingTo(expectedInvNet);
    }

    // =========================================================================
    // BR-INV-03 BOM: composed product → one COGS leg per stockable component
    // =========================================================================

    @Test
    void saleIssueComposedProduct_cogsLegPerComponent() {
        ProductDto comp1    = stockableProduct("BomComp1");
        ProductDto comp2    = stockableProduct("BomComp2");
        ProductDto composed = stockableProduct("BomComposed");

        productService.addComponent(composed.uid(),
                new AddComponentRequest(comp1.uid(), new BigDecimal("2")));
        productService.addComponent(composed.uid(),
                new AddComponentRequest(comp2.uid(), new BigDecimal("3")));

        // Receive both components
        dispatcher.dispatchOne(publishReceiptEvent("RCPT-BOM-C1", comp1,
                new BigDecimal("20"), new BigDecimal("100"), 1L));
        dispatcher.dispatchOne(publishReceiptEvent("RCPT-BOM-C2", comp2,
                new BigDecimal("30"), new BigDecimal("200"), 2L));

        assertThat(requireSoh(comp1.id()).getAvgCost()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(requireSoh(comp2.id()).getAvgCost()).isEqualByComparingTo(new BigDecimal("200"));

        // Sell 2 composed → comp1 ×4 @ 100 = 400, comp2 ×6 @ 200 = 1200 → total COGS 1600
        SalesInvoiceDto draft = makeSaleInvoice(composed.uid(), new BigDecimal("2"));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        assertThat(cogsBalance())
                .as("COGS must reflect cost of all BOM components")
                .isEqualByComparingTo(new BigDecimal("1600"));

        // Each component's on_hand_value decreased by cost of issued qty
        assertThat(requireSoh(comp1.id()).getOnHandValue())
                .as("comp1 on_hand_value = 20×100 − 4×100 = 1600")
                .isEqualByComparingTo(new BigDecimal("1600").setScale(4, RoundingMode.HALF_UP));
        assertThat(requireSoh(comp2.id()).getOnHandValue())
                .as("comp2 on_hand_value = 30×200 − 6×200 = 4800")
                .isEqualByComparingTo(new BigDecimal("4800").setScale(4, RoundingMode.HALF_UP));
    }

    // =========================================================================
    // BR-INV-03 edge: null avg_cost → COGS leg skipped, qty still deducts (no crash)
    // =========================================================================

    @Test
    void saleIssue_nullAvgCost_cogsLegSkippedQtyStillDeducts() {
        // Product created but never received → avg_cost remains null
        ProductDto product = stockableProduct("NoCostWidget");

        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), new BigDecimal("3"));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());

        // Must not throw — COGS leg is skipped per D-2 edge
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        // Quantity deducted
        StockOnHand soh = requireSoh(product.id());
        assertThat(soh.getQuantity())
                .as("quantity must be deducted even when avg_cost is null")
                .isEqualByComparingTo(new BigDecimal("-3"));

        // COGS GL: no posting made (balance stays at zero in this isolated test run)
        assertThat(cogsBalance())
                .as("COGS must remain zero when no avg_cost exists")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // BR-INV-05 (sale void): DR Inventory/CR COGS at original issue value; avg_cost unchanged
    // =========================================================================

    @Test
    void saleReversal_restoresInventoryAtOriginalCost_avgUnchanged() {
        ProductDto product = stockableProduct("VoidCogs-Widget");

        dispatcher.dispatchOne(publishReceiptEvent("RCPT-VOID-001", product,
                new BigDecimal("10"), new BigDecimal("500"), 1L));

        BigDecimal avgBeforeIssue  = requireSoh(product.id()).getAvgCost();  // 500
        BigDecimal valueBeforeIssue = requireSoh(product.id()).getOnHandValue(); // 5000

        // Sell 4
        SalesInvoiceDto draft = makeSaleInvoice(product.uid(), new BigDecimal("4"));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        // Void the sale
        salesInvoiceService.voidInvoice(draft.uid(), new VoidInvoiceRequest("test void"));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_VOIDED));

        StockOnHand soh = requireSoh(product.id());

        assertThat(soh.getOnHandValue())
                .as("on_hand_value must be fully restored after reversal")
                .isEqualByComparingTo(valueBeforeIssue);
        assertThat(soh.getAvgCost())
                .as("avg_cost must be unchanged after issue + reversal")
                .isEqualByComparingTo(avgBeforeIssue);

        // COGS net = 0 (issue DR cancelled by reversal CR)
        assertThat(cogsBalance())
                .as("COGS GL must net to zero after void")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Inventory net = original receipt value (issue + reversal cancel each other)
        assertThat(inventoryBalance())
                .as("Inventory GL must equal receipt value after void cancels the issue")
                .isEqualByComparingTo(new BigDecimal("5000").setScale(4, RoundingMode.HALF_UP));
    }

    // =========================================================================
    // BR-INV-05 (receipt void): DR GRNI / CR Inventory at original cost; avg re-derived
    // =========================================================================

    @Test
    void receiptReversal_backsOutAvgCostAndGl() {
        ProductDto product = stockableProduct("RcptReverse-Widget");

        // Receipt: 10 @ 500 → avg = 500, value = 5000
        dispatcher.dispatchOne(publishReceiptEvent("RCPT-REV-001", product,
                new BigDecimal("10"), new BigDecimal("500"), 1L));

        assertThat(requireSoh(product.id()).getAvgCost()).isEqualByComparingTo(new BigDecimal("500"));

        // Void the receipt
        StockReceiptVoidedPayload voidPayload = new StockReceiptVoidedPayload(
                "RCPT-REV-001", company.getId(), branch.getId(),
                List.of(new StockReceiptVoidedPayload.LineItem(
                        product.id(), product.uid(), null, new BigDecimal("10"))));
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.STOCK_RECEIPT_VOIDED,
                    DomainEventType.AGG_GOODS_RECEIPT, 99L, "RCPT-REV-001",
                    company.getId(), branch.getId(), voidPayload);
            return null;
        });
        dispatcher.dispatchOne(pendingEvent(DomainEventType.STOCK_RECEIPT_VOIDED));

        StockOnHand soh = requireSoh(product.id());
        assertThat(soh.getQuantity())
                .as("quantity must return to zero after receipt reversal")
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(soh.getOnHandValue())
                .as("on_hand_value must return to zero after receipt reversal")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // Inventory GL: DR 5000 (receipt) + CR 5000 (reversal) = 0
        assertThat(inventoryBalance())
                .as("Inventory GL must net to zero after receipt reversal")
                .isEqualByComparingTo(BigDecimal.ZERO);

        // GRNI GL: CR 5000 (receipt) + DR 5000 (reversal) = 0
        assertThat(grniBalance())
                .as("GRNI GL must net to zero after receipt reversal")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // BR-INV-07: setOpeningValue sets avg_cost + posts DR Inventory/CR OBE
    // and rejects a second call (once-per-product guard).
    // =========================================================================

    @Test
    void setOpeningValue_setsAvgAndPostsGl_oncePer_productGuard() {
        ProductDto product = stockableProduct("OBEWidget");

        // Set quantity-only opening balance — avg_cost stays null after this
        stockService.openingBalance(
                new OpeningBalanceRequest(product.uid(), new BigDecimal("50"), null));

        StockOnHand sohAfterQty = requireSoh(product.id());
        assertThat(sohAfterQty.getAvgCost())
                .as("avg_cost must remain null after qty-only opening balance")
                .isNull();

        // Set opening valuation: unit cost 200 → value = 50 × 200 = 10000
        setCtx();
        var obeResult = inventoryValuationService.setOpeningValue(
                new SetOpeningValuationRequest(sohAfterQty.getUid(), new BigDecimal("200")),
                LocalDate.now());

        assertThat(obeResult.openingCost())
                .isEqualByComparingTo(new BigDecimal("200"));
        assertThat(obeResult.openingValue())
                .isEqualByComparingTo(new BigDecimal("10000"));
        assertThat(obeResult.glEntryUid())
                .as("GL entry must be posted for opening valuation")
                .isNotBlank();

        // avg_cost now set
        assertThat(requireSoh(product.id()).getAvgCost())
                .isEqualByComparingTo(new BigDecimal("200"));

        // GL: DR Inventory / CR OBE each 10 000
        assertThat(inventoryBalance())
                .as("Inventory GL debited by opening value")
                .isEqualByComparingTo(new BigDecimal("10000").setScale(4, RoundingMode.HALF_UP));
        assertThat(obeBalance().negate())
                .as("Opening Balance Equity GL credited by opening value")
                .isEqualByComparingTo(new BigDecimal("10000").setScale(4, RoundingMode.HALF_UP));

        // Second call must be rejected (once-per-product guard BR-INV-07)
        SetOpeningValuationRequest secondRequest =
                new SetOpeningValuationRequest(sohAfterQty.getUid(), new BigDecimal("300"));
        LocalDate today = LocalDate.now();
        assertThatThrownBy(() -> inventoryValuationService.setOpeningValue(secondRequest, today))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already has a valuation");
    }

    // =========================================================================
    // BR-INV-06 (headline recon): Σ on_hand_value == GL 1300 Inventory balance
    // =========================================================================

    @Test
    void valuationReport_reconBarTies_afterReceiptSaleAndAdjust() {
        ProductDto p1 = stockableProduct("Recon-P1");
        ProductDto p2 = stockableProduct("Recon-P2");

        dispatcher.dispatchOne(publishReceiptEvent("RCPT-RC-001", p1,
                new BigDecimal("10"), new BigDecimal("400"), 1L));
        dispatcher.dispatchOne(publishReceiptEvent("RCPT-RC-002", p2,
                new BigDecimal("20"), new BigDecimal("300"), 2L));

        SalesInvoiceDto draft = makeSaleInvoice(p1.uid(), new BigDecimal("3"));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED));

        // Adjustment on p2
        setCtx();
        stockService.adjust(new AdjustStockRequest(
                p2.uid(), new BigDecimal("-2"), AdjustmentReason.DAMAGE, "test"));

        // Re-read the report
        setCtx();
        StockValuationReportDto report = valuationQuery.report(company.getId());

        assertThat(report.recon().ties())
                .as("Σ on_hand_value must equal GL 1300 balance (BR-INV-06 headline recon)")
                .isTrue();
        assertThat(report.recon().difference())
                .as("reconciliation difference must be exactly zero")
                .isEqualByComparingTo(BigDecimal.ZERO);
    }

    // =========================================================================
    // BR-INV-09 / ADR-0020 D-7: adjustment decrease → DR STOCK_ADJUSTMENT / CR Inventory
    // =========================================================================

    @Test
    void adjustmentDecrease_postsStockAdjustmentGl() {
        ProductDto product = stockableProduct("AdjVal-Widget");

        // Receive 20 @ 300 → avg = 300, value = 6000
        dispatcher.dispatchOne(publishReceiptEvent("RCPT-ADJ-001", product,
                new BigDecimal("20"), new BigDecimal("300"), 1L));

        // Adjust −5 (damage)
        setCtx();
        stockService.adjust(new AdjustStockRequest(
                product.uid(), new BigDecimal("-5"), AdjustmentReason.DAMAGE, "breakage"));

        // on_hand_value: 6000 − 5×300 = 4500
        assertThat(requireSoh(product.id()).getOnHandValue())
                .as("on_hand_value after adjustment decrease")
                .isEqualByComparingTo(new BigDecimal("4500").setScale(4, RoundingMode.HALF_UP));

        // GL: STOCK_ADJUSTMENT DR = 5 × 300 = 1500
        assertThat(stockAdjBalance())
                .as("STOCK_ADJUSTMENT DR must equal abs(qty) × avg_cost")
                .isEqualByComparingTo(new BigDecimal("1500").setScale(4, RoundingMode.HALF_UP));

        // Inventory: 6000 − 1500 = 4500
        assertThat(inventoryBalance())
                .as("Inventory GL net after receipt and adjustment decrease")
                .isEqualByComparingTo(new BigDecimal("4500").setScale(4, RoundingMode.HALF_UP));
    }

    // =========================================================================
    // Idempotency: re-dispatching the same STOCK.RECEIVED does NOT double-count
    // =========================================================================

    @Test
    void reDispatchSameReceiptEvent_doesNotDoubleCountAvgOrValue() {
        ProductDto product = stockableProduct("Idem-Widget");

        Long eventId = publishReceiptEvent("RCPT-IDEM-001", product,
                new BigDecimal("10"), new BigDecimal("500"), 1L);

        // First dispatch
        dispatcher.dispatchOne(eventId);
        StockOnHand after1 = requireSoh(product.id());
        assertThat(after1.getQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(after1.getOnHandValue()).isEqualByComparingTo(new BigDecimal("5000").setScale(4, RoundingMode.HALF_UP));

        // Second dispatch of the exact same event id — must be a no-op
        dispatcher.dispatchOne(eventId);
        StockOnHand after2 = requireSoh(product.id());
        assertThat(after2.getQuantity())
                .as("quantity must NOT be doubled on re-dispatch")
                .isEqualByComparingTo(new BigDecimal("10"));
        assertThat(after2.getOnHandValue())
                .as("on_hand_value must NOT be doubled on re-dispatch")
                .isEqualByComparingTo(new BigDecimal("5000").setScale(4, RoundingMode.HALF_UP));
        assertThat(after2.getAvgCost())
                .as("avg_cost must NOT be re-computed on re-dispatch")
                .isEqualByComparingTo(new BigDecimal("500"));
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void setCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "invval_root", true, company.getId(), branch.getId(), null));
    }

    private ProductDto stockableProduct(String name) {
        setCtx();
        ProductDto p = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD));
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
        // Re-read after line addition so the tendered amount equals the VAT-inclusive gross
        // computed by the service (BR-SALES-07 requires tenders cover the actual invoice gross).
        BigDecimal actualGross = salesInvoiceService.getByUid(draft.uid()).grossTotalAmount();
        salesInvoiceService.addPayment(draft.uid(),
                new AddPaymentRequest(TenderType.CASH, actualGross, "TZS", null));
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

    /** Net balance for a GL account: SUM(debit) − SUM(credit). Returns ZERO when null. */
    private BigDecimal safeBalance(Long accountId) {
        BigDecimal b = journalLines.accountBalance(company.getId(), accountId);
        return b != null ? b : BigDecimal.ZERO;
    }

    // Account ID helpers — use GlConfigRepository directly (no MANDATORY TX needed)
    private BigDecimal inventoryBalance() {
        return safeBalance(glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.INVENTORY)
                .orElseThrow().getAccountId());
    }

    private BigDecimal grniBalance() {
        return safeBalance(glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.GRNI)
                .orElseThrow().getAccountId());
    }

    private BigDecimal cogsBalance() {
        return safeBalance(glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.COGS)
                .orElseThrow().getAccountId());
    }

    private BigDecimal apBalance() {
        return safeBalance(glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.ACCOUNTS_PAYABLE)
                .orElseThrow().getAccountId());
    }

    private BigDecimal purchasesBalance() {
        return safeBalance(glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.PURCHASES)
                .orElseThrow().getAccountId());
    }

    private BigDecimal obeBalance() {
        return safeBalance(glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.OPENING_BALANCE_EQUITY)
                .orElseThrow().getAccountId());
    }

    private BigDecimal stockAdjBalance() {
        return safeBalance(glConfigRepo
                .findByCompanyIdAndConfigKey(company.getId(), GlConfigKey.STOCK_ADJUSTMENT)
                .orElseThrow().getAccountId());
    }
}
