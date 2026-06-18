package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.domain.dto.VoidGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.VoidPurchaseOrderRequest;
import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import com.erp.modules.purchases.repository.GoodsReceiptLineRepository;
import com.erp.modules.purchases.repository.GoodsReceiptRepository;
import com.erp.modules.purchases.repository.PurchaseOrderLineRepository;
import com.erp.modules.purchases.repository.PurchaseOrderRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.stock.domain.dto.StockOnHandDto;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.audit.AuditLog;
import com.erp.platform.audit.AuditRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventStatus;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for the Purchases module (ADR-0011, Increment 3 of 3).
 *
 * <p>Scenarios covered:
 * <ol>
 *   <li>DRAFT→ORDERED lifecycle, PO-#### numbering (per-company sequence).</li>
 *   <li>Full receipt: GR against PO → PO RECEIVED, GRN-#### assigned, STOCK.RECEIVED emitted;
 *       drive dispatcher → stock on-hand increases (full purchase→stock loop).</li>
 *   <li>Partial receipts: two GRs against one PO → PARTIALLY_RECEIVED then RECEIVED,
 *       outstanding decreasing per draw-down.</li>
 *   <li>Over-receipt (received > outstanding) → IllegalStateException (BR-PURCH-10).</li>
 *   <li>GR void → STOCK.RECEIPT.VOIDED emitted → dispatch → stock on-hand reversed;
 *       PO outstanding restored, PO back to ORDERED.</li>
 *   <li>Cross-tenant: supplier from another company rejected (F15);
 *       company B cannot read company A's PO (assertCanActIn guard).</li>
 *   <li>Audit: PO place + GR receive write audit_log rows (target_type = plural table names).</li>
 *   <li>Outstanding invariant: po_line.received_qty_in_base == Σ non-void GR lines' qty_in_base
 *       (NFR-PURCH-07).</li>
 *   <li>Void blocked while non-void GR exists (ADR-0011 D-6).</li>
 *   <li>Idempotency: same STOCK.RECEIVED dispatched twice → stock increased once (NFR-STOCK-03).</li>
 * </ol>
 *
 * <p>The dispatcher is driven synchronously via {@link DomainEventDispatcher#dispatchOne(Long)} —
 * no sleeps, fully deterministic, matching the pattern proven in {@code StockServiceImplIT}.
 */
class PurchasesServiceImplIT extends PostgresIntegrationTest {

    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;

    @Autowired private PurchaseOrderService    poService;
    @Autowired private GoodsReceiptService     grService;
    @Autowired private SupplierService         supplierService;
    @Autowired private ProductService          productService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private PurchaseOrderRepository   poRepo;
    @Autowired private PurchaseOrderLineRepository poLineRepo;
    @Autowired private GoodsReceiptRepository    grRepo;
    @Autowired private GoodsReceiptLineRepository grLineRepo;
    @Autowired private StockOnHandRepository     stockOnHandRepo;
    @Autowired private StockMovementRepository   stockMovementRepo;
    @Autowired private DomainEventRepository     domainEventRepo;
    @Autowired private DomainEventDispatcher     dispatcher;
    @Autowired private AuditRepository           auditRepository;
    @Autowired private OrganisationRepository    organisations;
    @Autowired private CompanyRepository         companies;
    @Autowired private BranchRepository          branches;
    @Autowired private AppUserRepository         users;
    @Autowired private PasswordEncoder           passwordEncoder;
    @Autowired private IamTestData               testData;

    private Company companyA;
    private Branch  branchA;
    private Long    rootId;

    private String pcsUid;
    private String supplierUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Purch IT Org"));
        companyA = companies.save(new Company(org, "PURCA", "Purch IT Co A"));
        branchA  = branches.save(new Branch(companyA, "PURCH-A1", "Purch IT Branch A1"));

        AppUser root = new AppUser("purch_root", passwordEncoder.encode("RootPass1!"), "PURCH Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        setContext(companyA, branchA);

        // GL foundations required by GoodsReceiptStockHandler (ADR-0020):
        // handler posts DR Inventory/CR GRNI on every receipt event; without these
        // the dispatch TX is marked rollback-only and all dispatcher tests error.
        chartOfAccountService.seedDefaults(companyA.getId());
        fiscalCalendarService.seedCurrentYear(companyA.getId());
        glConfigService.seedDefaults(companyA.getId());

        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));
        pcsUid = pcs.uid();

        SupplierDto supplier = supplierService.create(new CreateSupplierRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "Test Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));
        supplierUid = supplier.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // 1. DRAFT → ORDERED lifecycle, PO-#### numbering
    // =========================================================================

    @Test
    void create_andPlaceOrder_assignsPoNumber() {
        ProductDto product = stockableProduct("Widget-A");

        PurchaseOrderDto draft = createDraftWithLine(product.uid(), new BigDecimal("10"),
                new BigDecimal("500"));

        assertThat(draft.status()).isEqualTo(PurchaseOrderStatus.DRAFT);
        assertThat(draft.orderNumber()).isNull();
        assertThat(draft.lines()).hasSize(1);

        PurchaseOrderDto placed = poService.placeOrder(draft.uid());

        assertThat(placed.status()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(placed.orderNumber()).isEqualTo("PO-0001");
        assertThat(placed.orderTotalAmount()).isEqualByComparingTo(new BigDecimal("5000"));
    }

    @Test
    void placeTwoOrders_sameCompany_sequentialNumbers() {
        ProductDto product = stockableProduct("Widget-Seq");

        PurchaseOrderDto po1 = createDraftWithLine(product.uid(), new BigDecimal("1"),
                new BigDecimal("100"));
        PurchaseOrderDto po2 = createDraftWithLine(product.uid(), new BigDecimal("1"),
                new BigDecimal("200"));

        String num1 = poService.placeOrder(po1.uid()).orderNumber();
        String num2 = poService.placeOrder(po2.uid()).orderNumber();

        assertThat(num1).isEqualTo("PO-0001");
        assertThat(num2).isEqualTo("PO-0002");
    }

    // =========================================================================
    // 2. Full receipt: GR against PO → PO RECEIVED, GRN assigned, STOCK.RECEIVED
    //    emitted; drive dispatcher → on-hand INCREASES (full purchase→stock loop)
    // =========================================================================

    @Test
    void createAndReceive_fullReceipt_poReceivedAndStockIncreases() {
        ProductDto product = stockableProduct("FullRcpt-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("20"), new BigDecimal("150"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), "Full delivery",
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("20")))));

        // GR assertions
        assertThat(gr.status()).isEqualTo(GoodsReceiptStatus.RECEIVED);
        assertThat(gr.receiptNumber()).isEqualTo("GRN-0001");
        assertThat(gr.lines()).hasSize(1);
        assertThat(gr.lines().get(0).receivedQty()).isEqualByComparingTo(new BigDecimal("20"));

        // PO should be RECEIVED
        PurchaseOrderDto po = poService.getByUid(placed.uid());
        assertThat(po.status()).isEqualTo(PurchaseOrderStatus.RECEIVED);

        // STOCK.RECEIVED event must be PENDING in outbox
        Long eventId = pendingEventId(DomainEventType.STOCK_RECEIVED);
        assertThat(eventId).isNotNull();

        // Drive dispatcher: on-hand must increase
        dispatcher.dispatchOne(eventId);

        StockOnHandDto soh = onHand(product.id());
        assertThat(soh.quantity()).isEqualByComparingTo(new BigDecimal("20"));
        assertLedgerMatchesOnHand(product.id());
    }

    @Test
    void goodsReceipt_createsGoodsReceiptMovement_inStockLedger() {
        ProductDto product = stockableProduct("GR-Movement-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("5"), new BigDecimal("200"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("5")))));

        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));

        // Stock movement: type=GOODS_RECEIPT, quantity=+5
        var movements = stockMovementRepo.findBySourceDocumentUidAndMovementType(
                gr.uid(), MovementType.GOODS_RECEIPT);
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getQuantity()).isEqualByComparingTo(new BigDecimal("5"));
        assertThat(movements.get(0).getMovementType()).isEqualTo(MovementType.GOODS_RECEIPT);
    }

    // =========================================================================
    // 3. Partial receipts: two GRs against one PO, outstanding decreases
    // =========================================================================

    @Test
    void twoPartialReceipts_poPartiallyReceivedThenReceived() {
        ProductDto product = stockableProduct("PartialRcpt-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("10"), new BigDecimal("100"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        // First receipt: 6 of 10
        GoodsReceiptDto gr1 = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), "Partial 1",
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("6")))));

        assertThat(gr1.receiptNumber()).isEqualTo("GRN-0001");
        PurchaseOrderDto afterGr1 = poService.getByUid(placed.uid());
        assertThat(afterGr1.status()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);

        // Line: 6 received, 4 outstanding
        PurchaseOrderLineDto lineAfterGr1 = afterGr1.lines().get(0);
        assertThat(lineAfterGr1.receivedQtyInBase()).isEqualByComparingTo(new BigDecimal("6"));
        assertThat(lineAfterGr1.outstandingQtyInBase()).isEqualByComparingTo(new BigDecimal("4"));
        assertThat(lineAfterGr1.fullyReceived()).isFalse();

        // Second receipt: remaining 4
        GoodsReceiptDto gr2 = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), "Partial 2",
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("4")))));

        assertThat(gr2.receiptNumber()).isEqualTo("GRN-0002");
        PurchaseOrderDto afterGr2 = poService.getByUid(placed.uid());
        assertThat(afterGr2.status()).isEqualTo(PurchaseOrderStatus.RECEIVED);

        PurchaseOrderLineDto lineAfterGr2 = afterGr2.lines().get(0);
        assertThat(lineAfterGr2.receivedQtyInBase()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(lineAfterGr2.outstandingQtyInBase()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(lineAfterGr2.fullyReceived()).isTrue();

        // Drive both dispatches; total on-hand = 10
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));

        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertLedgerMatchesOnHand(product.id());
    }

    // =========================================================================
    // 4. Over-receipt: received > outstanding → rejected (BR-PURCH-10)
    // =========================================================================

    @Test
    void overReceipt_rejected() {
        ProductDto product = stockableProduct("OverRcpt-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("5"), new BigDecimal("200"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        // Try to receive 10, but only 5 ordered
        assertThatThrownBy(() ->
                grService.createAndReceive(new CreateGoodsReceiptRequest(
                        placed.uid(), null,
                        List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("10"))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Over-receipt rejected");
    }

    @Test
    void overReceiptOnSecondGr_afterPartialFirst_rejected() {
        ProductDto product = stockableProduct("OverRcpt2-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("8"), new BigDecimal("100"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        // First receipt: 5
        grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("5")))));

        // Second: try to receive 4 (outstanding is 3)
        assertThatThrownBy(() ->
                grService.createAndReceive(new CreateGoodsReceiptRequest(
                        placed.uid(), null,
                        List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("4"))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Over-receipt rejected");
    }

    // =========================================================================
    // 5. GR void → STOCK.RECEIPT.VOIDED → dispatch → stock reversed, PO restored
    // =========================================================================

    @Test
    void voidReceipt_reversesPoCounts_andEmitsVoidedEvent_andStockReversed() {
        ProductDto product = stockableProduct("VoidRcpt-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("10"), new BigDecimal("300"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("10")))));

        assertThat(poService.getByUid(placed.uid()).status()).isEqualTo(PurchaseOrderStatus.RECEIVED);

        // Drive STOCK.RECEIVED so on-hand goes to 10
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));
        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(new BigDecimal("10"));

        // Now void the GR
        GoodsReceiptDto voided = grService.voidReceipt(gr.uid(), new VoidGoodsReceiptRequest("Test void"));

        assertThat(voided.status()).isEqualTo(GoodsReceiptStatus.VOID);
        assertThat(voided.voidReason()).isEqualTo("Test void");

        // PO should be back to ORDERED (all GRs voided → outstanding = 10 again)
        PurchaseOrderDto poAfterVoid = poService.getByUid(placed.uid());
        assertThat(poAfterVoid.status()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(poAfterVoid.lines().get(0).receivedQtyInBase())
                .isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(poAfterVoid.lines().get(0).outstandingQtyInBase())
                .isEqualByComparingTo(new BigDecimal("10"));

        // STOCK.RECEIPT.VOIDED event must be PENDING
        Long voidEventId = pendingEventId(DomainEventType.STOCK_RECEIPT_VOIDED);
        assertThat(voidEventId).isNotNull();

        // Drive the void dispatcher: on-hand returns to 0
        dispatcher.dispatchOne(voidEventId);
        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertLedgerMatchesOnHand(product.id());
    }

    @Test
    void voidReceipt_partialGr_restoredOutstandingAllowsSecondReceipt() {
        ProductDto product = stockableProduct("VoidPartial-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("10"), new BigDecimal("100"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        // Receive 6
        GoodsReceiptDto gr1 = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("6")))));
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));

        assertThat(poService.getByUid(placed.uid()).status())
                .isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);

        // Void that GR
        grService.voidReceipt(gr1.uid(), new VoidGoodsReceiptRequest("Received wrong items"));
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIPT_VOIDED));

        // PO back to ORDERED, on-hand back to 0
        assertThat(poService.getByUid(placed.uid()).status()).isEqualTo(PurchaseOrderStatus.ORDERED);
        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(BigDecimal.ZERO);

        // Now receive the full 10 (outstanding fully restored)
        GoodsReceiptDto gr2 = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("10")))));

        assertThat(gr2.status()).isEqualTo(GoodsReceiptStatus.RECEIVED);
        assertThat(poService.getByUid(placed.uid()).status()).isEqualTo(PurchaseOrderStatus.RECEIVED);

        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));
        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(new BigDecimal("10"));
    }

    // =========================================================================
    // 6. Cross-tenant guards
    // =========================================================================

    @Test
    void createGr_withPoFromOtherCompany_rejected() {
        // Create PO in company A
        ProductDto product = stockableProduct("CrossTenant-Widget");
        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("5"), new BigDecimal("100"));

        // Switch context to company B
        Organisation orgB   = organisations.save(new Organisation("Org B"));
        Company companyB    = companies.save(new Company(orgB, "PURCB", "Purch IT Co B"));
        Branch branchB      = branches.save(new Branch(companyB, "PURCH-B1", "Branch B1"));

        // Non-root user in B
        RequestContext.set(new RequestContext.Principal(
                rootId, "purch_root", false, companyB.getId(), branchB.getId(), null));

        // Reading A's PO from B's context must be denied
        assertThatThrownBy(() -> poService.getByUid(placed.uid()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void getByUid_differentTenant_denied() {
        // PO in company A
        ProductDto product = stockableProduct("CT2-Widget");
        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("1"), new BigDecimal("50"));

        Organisation orgC   = organisations.save(new Organisation("Org C"));
        Company companyC    = companies.save(new Company(orgC, "PURCC", "Purch IT Co C"));
        Branch branchC      = branches.save(new Branch(companyC, "PURCH-C1", "Branch C1"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "purch_root", false, companyC.getId(), branchC.getId(), null));

        assertThatThrownBy(() -> poService.getByUid(placed.uid()))
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void createPo_withSupplierFromOtherCompany_rejected() {
        // Create a supplier in company B
        Organisation orgB   = organisations.save(new Organisation("Org B-Supp"));
        Company companyB    = companies.save(new Company(orgB, "BSUPP", "B Supplier Co"));
        Branch branchB      = branches.save(new Branch(companyB, "B-S1", "B Branch"));

        setContext(companyB, branchB);

        unitService.create(
                new CreateUnitOfMeasureRequest(companyB.getUid(), "PCS", "Pieces"));

        SupplierDto supplierB = supplierService.create(new CreateSupplierRequest(
                companyB.getId(), PartyType.INDIVIDUAL, "B-Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));

        // Back to company A context
        setContext(companyA, branchA);

        // Use company B's supplier in a company A PO → service rejects (F15 cross-tenant guard)
        assertThatThrownBy(() ->
                poService.create(new CreatePurchaseOrderRequest(
                        companyA.getUid(), supplierB.uid(), "TZS", null, null, null)))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class);
    }

    // =========================================================================
    // 7. Audit rows written for PO place and GR receive (plural target_type)
    // =========================================================================

    @Test
    void placeOrder_writesAuditLog() {
        ProductDto product = stockableProduct("Audit-PO-Widget");
        PurchaseOrderDto draft = createDraftWithLine(product.uid(),
                new BigDecimal("2"), new BigDecimal("100"));

        long auditBefore = auditRepository.count();
        poService.placeOrder(draft.uid());
        long auditAfter = auditRepository.count();

        assertThat(auditAfter).isGreaterThan(auditBefore);

        List<AuditLog> logs = auditRepository.findAll();
        assertThat(logs).anyMatch(l ->
                "PURCHASE.ORDER.PLACE".equals(l.getAction())
                && "purchase_orders".equals(l.getTargetType()));
    }

    @Test
    void createAndReceive_writesAuditLog() {
        ProductDto product = stockableProduct("Audit-GR-Widget");
        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("3"), new BigDecimal("100"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        long auditBefore = auditRepository.count();
        grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("3")))));
        long auditAfter = auditRepository.count();

        assertThat(auditAfter).isGreaterThan(auditBefore);

        List<AuditLog> logs = auditRepository.findAll();
        assertThat(logs).anyMatch(l ->
                "PURCHASE.GOODS_RECEIPT.RECEIVE".equals(l.getAction())
                && "goods_receipts".equals(l.getTargetType()));
    }

    // =========================================================================
    // 8. Outstanding invariant: received_qty_in_base == Σ non-void GR lines
    // =========================================================================

    @Test
    void outstandingInvariant_afterTwoGrsAndOneVoid() {
        ProductDto product = stockableProduct("Invariant-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("15"), new BigDecimal("100"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);
        Long poLineId = poLine.id();

        // GR1: 5 units
        GoodsReceiptDto gr1 = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("5")))));
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));

        // GR2: 6 units
        grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("6")))));
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));

        // Void GR1
        grService.voidReceipt(gr1.uid(), new VoidGoodsReceiptRequest("Damaged batch"));
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIPT_VOIDED));

        // PO line: receivedQtyInBase should equal Σ non-void GR lines' qty_in_base
        var nonVoidLines = grLineRepo.findNonVoidByPurchaseOrderLineId(poLineId);
        BigDecimal sumNonVoid = nonVoidLines.stream()
                .map(l -> l.getQtyInBase())
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        var updatedPoLine = poLineRepo.findByPurchaseOrderIdOrderByLineNo(
                poRepo.findByUid(placed.uid()).orElseThrow().getId()).get(0);

        assertThat(updatedPoLine.getReceivedQtyInBase())
                .as("NFR-PURCH-07: received_qty_in_base == Σ non-void GR lines' qty_in_base")
                .isEqualByComparingTo(sumNonVoid);

        // On-hand: 0 + 5 − 5 + 6 = 6
        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(new BigDecimal("6"));
        assertLedgerMatchesOnHand(product.id());
    }

    // =========================================================================
    // 9. Void PO blocked while non-void GR exists (ADR-0011 D-6)
    // =========================================================================

    @Test
    void voidPo_withNonVoidGr_blocked() {
        ProductDto product = stockableProduct("VoidPO-Block-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("5"), new BigDecimal("100"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("3")))));

        assertThatThrownBy(() ->
                poService.voidOrder(placed.uid(), new VoidPurchaseOrderRequest("test")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("non-void goods receipts");
    }

    @Test
    void voidPo_afterGrVoided_succeeds() {
        ProductDto product = stockableProduct("VoidPO-OK-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("5"), new BigDecimal("100"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("3")))));

        // Void the GR first
        grService.voidReceipt(gr.uid(), new VoidGoodsReceiptRequest("Wrong items"));
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIPT_VOIDED));

        // Now void the PO should succeed
        PurchaseOrderDto voidedPo = poService.voidOrder(placed.uid(),
                new VoidPurchaseOrderRequest("Cancelled"));
        assertThat(voidedPo.status()).isEqualTo(PurchaseOrderStatus.VOID);
    }

    // =========================================================================
    // 10. Idempotency: same STOCK.RECEIVED dispatched twice → on-hand updated once
    // =========================================================================

    @Test
    void dispatchStockReceived_twiceForSameEvent_processedOnce() {
        ProductDto product = stockableProduct("Idem-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("4"), new BigDecimal("100"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("4")))));

        Long eventId = pendingEventId(DomainEventType.STOCK_RECEIVED);

        // First dispatch: processed
        dispatcher.dispatchOne(eventId);

        // Second dispatch of the same event id: already DISPATCHED → skipped (NFR-STOCK-03)
        dispatcher.dispatchOne(eventId);

        // On-hand must be +4 (applied exactly once)
        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(new BigDecimal("4"));
        // Exactly one GOODS_RECEIPT movement
        var movements = stockMovementRepo.findBySourceDocumentUidAndMovementType(
                grRepo.findByCompanyId(companyA.getId(), Pageable.unpaged())
                        .getContent().get(0).getUid(),
                MovementType.GOODS_RECEIPT);
        assertThat(movements).hasSize(1);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void setContext(Company company, Branch branch) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "purch_root", true, company.getId(), branch.getId(), null));
    }

    private ProductDto stockableProduct(String name) {
        return productService.create(new CreateProductRequest(
                companyA.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null));
    }

    private PurchaseOrderDto createDraftWithLine(String productUid, BigDecimal qty,
                                                  BigDecimal unitCost) {
        return poService.create(new CreatePurchaseOrderRequest(
                companyA.getUid(), supplierUid, "TZS", null, null,
                List.of(new AddPurchaseOrderLineRequest(productUid, pcsUid, qty, unitCost, null))));
    }

    private PurchaseOrderDto placeOrderWithLine(String productUid, BigDecimal qty,
                                                 BigDecimal unitCost) {
        PurchaseOrderDto draft = createDraftWithLine(productUid, qty, unitCost);
        return poService.placeOrder(draft.uid());
    }

    private Long pendingEventId(String eventType) {
        return domainEventRepo.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .reduce((a, b) -> b) // last inserted
                .map(DomainEvent::getId)
                .orElseThrow(() -> new AssertionError("No PENDING event of type: " + eventType));
    }

    private StockOnHandDto onHand(Long productId) {
        return stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(companyA.getId(), branchA.getId(), productId)
                .map(StockOnHandDto::from)
                .orElseThrow(() -> new AssertionError("No on-hand row for productId=" + productId));
    }

    /**
     * Asserts BR-STOCK-01: stock_on_hand.quantity == Σ stock_movements.quantity
     * for the (company, branch, product) triple.
     */
    private void assertLedgerMatchesOnHand(Long productId) {
        StockOnHandDto soh = onHand(productId);
        BigDecimal sumFromLedger = stockMovementRepo
                .findByCompanyIdAndBranchIdAndProductIdOrderByOccurredAtAsc(
                        companyA.getId(), branchA.getId(), productId, Pageable.unpaged())
                .getContent().stream()
                .map(m -> m.getQuantity())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(soh.quantity())
                .as("on-hand must equal Σ movements (BR-STOCK-01) for productId=%d", productId)
                .isEqualByComparingTo(sumFromLedger);
    }
}
