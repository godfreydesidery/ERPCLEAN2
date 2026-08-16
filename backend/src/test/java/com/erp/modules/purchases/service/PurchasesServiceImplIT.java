package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
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
import com.erp.modules.products.domain.dto.CreateBulkPackRequest;
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
import com.erp.modules.purchases.domain.dto.DirectGoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.DirectGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.PurchaseSettingsDto;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseSettingsRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptPrintDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderApprovalSnapshotDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.domain.dto.VoidGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.VoidPurchaseOrderRequest;
import com.erp.modules.purchases.domain.enums.GoodsReceiptStatus;
import com.erp.modules.purchases.domain.enums.PoApprovalStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
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
    @Autowired private DirectGoodsReceiptService directGrService;
    @Autowired private SupplierService         supplierService;
    @Autowired private ProductService          productService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private PurchaseSettingsService purchaseSettingsService;
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
        root.setOrganisationId(org.getId());
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

    /**
     * K9 — the printed vendor GRN's read model, against a real database.
     *
     * <p>The unit tests cover the margin and VAT arithmetic; what only a database can prove is that
     * the native SQL behind the derived columns actually resolves. In particular <b>Last CP</b>: the
     * second delivery of a product must print the FIRST delivery's cost, not its own. Both receipts
     * land in the same test at effectively the same instant, so this also exercises the id tiebreak
     * that keeps "the previous receipt" deterministic when two share a timestamp.
     */
    @Test
    void printByUid_carriesTheDerivedColumnsThePrintedNoteNeeds() {
        ProductDto product = stockableProduct("PrintNote-Widget");

        // First delivery at 100 — this is what the second one's "Last CP" must show.
        PurchaseOrderDto first = placeOrderWithLine(product.uid(),
                new BigDecimal("10"), new BigDecimal("100"));
        grService.createAndReceive(new CreateGoodsReceiptRequest(
                first.uid(), null,
                List.of(new GoodsReceiptLineRequest(first.lines().get(0).uid(), new BigDecimal("10")))));

        // Second delivery of the SAME product, at a higher cost.
        PurchaseOrderDto second = placeOrderWithLine(product.uid(),
                new BigDecimal("5"), new BigDecimal("120"));
        GoodsReceiptDto gr2 = grService.createAndReceive(new CreateGoodsReceiptRequest(
                second.uid(), null,
                List.of(new GoodsReceiptLineRequest(second.lines().get(0).uid(), new BigDecimal("5")))));

        GoodsReceiptPrintDto printed = grService.printByUid(gr2.uid());

        assertThat(printed.receiptNumber()).isEqualTo(gr2.receiptNumber());
        assertThat(printed.status()).isEqualTo("RECEIVED");
        assertThat(printed.supplierName()).isNotBlank();
        assertThat(printed.branchName()).isNotBlank();
        assertThat(printed.purchaseOrderNumber()).isEqualTo(second.orderNumber());
        assertThat(printed.lines()).hasSize(1);

        var line = printed.lines().get(0);
        assertThat(line.productCode()).isEqualTo(product.code());
        assertThat(line.costPrice()).isEqualByComparingTo("120");
        assertThat(line.lastCostPrice()).isEqualByComparingTo("100");
        assertThat(line.amount()).isEqualByComparingTo("600");

        // Foot ties to the lines, and Total = Net + VAT + Rounding by construction.
        assertThat(printed.netAmount()).isEqualByComparingTo("600.00");
        assertThat(printed.totalAmount()).isEqualByComparingTo(
                printed.netAmount().add(printed.vatAmount()).add(printed.roundingAmount()));
        assertThat(printed.vatBands()).isNotEmpty();
    }

    /** A first-ever receipt of a product has no previous cost — the column must be blank, not zero. */
    @Test
    void printByUid_leavesLastCostBlankOnAFirstReceipt() {
        ProductDto product = stockableProduct("FirstEver-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("3"), new BigDecimal("777"));
        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(placed.lines().get(0).uid(), new BigDecimal("3")))));

        GoodsReceiptPrintDto printed = grService.printByUid(gr.uid());

        assertThat(printed.lines()).hasSize(1);
        assertThat(printed.lines().get(0).lastCostPrice()).isNull();
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

    // Saidi #4: with a per-company over-receipt tolerance, a receipt may exceed the ordered amount.
    @Test
    void overReceipt_withinCompanyTolerance_allowed() {
        setReceiptTolerance(new BigDecimal("5"));   // 5%
        ProductDto product = stockableProduct("TolRcpt-Rice");
        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("100"), new BigDecimal("200"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        // Receive 105 — exactly 5% over the 100 ordered — allowed.
        assertThatCode(() ->
                grService.createAndReceive(new CreateGoodsReceiptRequest(
                        placed.uid(), null,
                        List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("105"))))))
                .doesNotThrowAnyException();
    }

    @Test
    void overReceipt_beyondCompanyTolerance_rejected() {
        setReceiptTolerance(new BigDecimal("5"));   // 5%
        ProductDto product = stockableProduct("TolRcpt2-Rice");
        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("100"), new BigDecimal("200"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        // Receive 106 — beyond the 5% tolerance (ceiling 105) — still rejected.
        assertThatThrownBy(() ->
                grService.createAndReceive(new CreateGoodsReceiptRequest(
                        placed.uid(), null,
                        List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("106"))))))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Over-receipt rejected");
    }

    private void setReceiptTolerance(BigDecimal pct) {
        purchaseSettingsService.update(new UpdatePurchaseSettingsRequest(
                companyA.getUid(), false, null, "TZS",
                null, null, null, null, null, null, null, pct));
    }

    // Regression: a partial update (the settings form only sends approval + receipt-tolerance) must
    // NOT wipe fields it doesn't surface (matchTolerancePct etc.) back to null.
    @Test
    void purchaseSettings_partialUpdate_preservesUnsurfacedFields() {
        // First, set a match-tolerance (a field no UI currently surfaces).
        purchaseSettingsService.update(new UpdatePurchaseSettingsRequest(
                companyA.getUid(), false, null, "TZS",
                null, null, new BigDecimal("2.5"), null, null, null, null, null));

        // Then a settings-form-shaped partial update: approval + receiptTolerance only, matchTolerance omitted.
        PurchaseSettingsDto after = purchaseSettingsService.update(new UpdatePurchaseSettingsRequest(
                companyA.getUid(), false, null, "TZS",
                null, null, null, null, null, null, null, new BigDecimal("5")));

        assertThat(after.matchTolerancePct())
                .as("a field the form doesn't send must be preserved, not wiped to null")
                .isEqualByComparingTo("2.5");
        assertThat(after.receiptTolerancePct())
                .as("a field the form owns is still set (and clearable) as sent")
                .isEqualByComparingTo("5");
    }

    // Persona re-test: a negative tolerance is rejected with a plain, field-name-free message.
    @Test
    void setReceiptTolerance_negative_friendlyMessage() {
        assertThatThrownBy(() -> purchaseSettingsService.update(new UpdatePurchaseSettingsRequest(
                companyA.getUid(), false, null, "TZS",
                null, null, null, null, null, null, null, new BigDecimal("-5"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Over-receipt tolerance cannot be negative.");
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
    // 11. computeQtyInBase guard: unconfigured unit → IllegalStateException
    // =========================================================================

    @Test
    void addLine_withUnconfiguredUnit_throwsIllegalState() {
        ProductDto product = stockableProduct("UnitGuard-Widget");

        // Create a second unit that is NOT the product's base and NOT a bulk-pack.
        UnitOfMeasureDto kgUnit = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "KG", "Kilograms"));

        PurchaseOrderDto draft = poService.create(new CreatePurchaseOrderRequest(
                companyA.getUid(), supplierUid, "TZS", null, null, null));

        assertThatThrownBy(() -> poService.addLine(draft.uid(),
                new AddPurchaseOrderLineRequest(
                        product.uid(), kgUnit.uid(), new BigDecimal("5"), new BigDecimal("100"), null)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("is not a valid unit for");
    }

    @Test
    void addLine_withBaseUnit_succeeds() {
        ProductDto product = stockableProduct("BaseUnit-Widget");

        // pcsUid is the product's base unit — must succeed.
        PurchaseOrderDto draft = createDraftWithLine(product.uid(), new BigDecimal("3"),
                new BigDecimal("200"));

        assertThat(draft.lines()).hasSize(1);
        assertThat(draft.lines().get(0).orderedQty()).isEqualByComparingTo(new BigDecimal("3"));
        // qty_in_base == qty (factor 1) for the base unit.
        assertThat(draft.lines().get(0).orderedQtyInBase()).isEqualByComparingTo(new BigDecimal("3"));
    }

    @Test
    void addLine_withConfiguredBulkPackUnit_succeeds() {
        ProductDto product = stockableProduct("PackUnit-Widget");

        // Add a CARTON bulk pack (factor 12) to the product.
        UnitOfMeasureDto cartonUnit = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "CTN", "Carton"));
        productService.addBulkPack(product.uid(),
                new CreateBulkPackRequest(cartonUnit.uid(), new BigDecimal("12")));

        PurchaseOrderDto draft = poService.create(new CreatePurchaseOrderRequest(
                companyA.getUid(), supplierUid, "TZS", null, null, null));
        poService.addLine(draft.uid(), new AddPurchaseOrderLineRequest(
                product.uid(), cartonUnit.uid(), new BigDecimal("2"), new BigDecimal("1200"), null));

        PurchaseOrderDto updated = poService.getByUid(draft.uid());
        assertThat(updated.lines()).hasSize(1);
        // 2 cartons × 12 = 24 in base.
        assertThat(updated.lines().get(0).orderedQtyInBase()).isEqualByComparingTo(new BigDecimal("24"));
    }

    // =========================================================================
    // 12. K4 (Kilimanjaro 2026-08-08) — a receipt in a PACK unit must value stock at the
    //     BASE-unit cost. Regression guard: the STOCK.RECEIVED payload used to carry the PO
    //     line's per-ORDER-unit cost next to the base quantity, and the stock handler multiplies
    //     the two — inflating inventory and GL 1300 by the pack factor on every receipt. This is
    //     invisible while the purchase unit equals the base unit, which is why it stayed dormant.
    // =========================================================================

    @Test
    void receiveInPackUnit_valuesStockAtTheBaseUnitCost() {
        ProductDto product = stockableProduct("PackCost-Soap");

        // Base unit is PCS; the supplier sells by the 12-piece carton.
        UnitOfMeasureDto carton = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "CTN", "Carton of 12"));
        productService.addBulkPack(product.uid(),
                new CreateBulkPackRequest(carton.uid(), new BigDecimal("12")));

        // 2 cartons at 1,200 per CARTON = 2,400 spent = 24 pieces at 100 each.
        PurchaseOrderDto draft = poService.create(new CreatePurchaseOrderRequest(
                companyA.getUid(), supplierUid, "TZS", null, null,
                List.of(new AddPurchaseOrderLineRequest(
                        product.uid(), carton.uid(),
                        new BigDecimal("2"), new BigDecimal("1200"), null))));
        PurchaseOrderDto placed = poService.placeOrder(draft.uid());
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("2")))));

        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));

        var soh = stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(
                        companyA.getId(), branchA.getId(), product.id())
                .orElseThrow(() -> new AssertionError("No on-hand row for the received product"));

        assertThat(soh.getQuantity())
                .as("2 cartons × 12 = 24 base units")
                .isEqualByComparingTo(new BigDecimal("24"));
        assertThat(soh.getOnHandValue())
                .as("K4: on_hand_value must be what was SPENT (2 × 1,200 = 2,400), "
                        + "not qtyInBase × per-carton cost (24 × 1,200 = 28,800)")
                .isEqualByComparingTo(new BigDecimal("2400"));
        assertThat(soh.getAvgCost())
                .as("K4: the moving average is per BASE unit — 2,400 ÷ 24 = 100 per piece")
                .isEqualByComparingTo(new BigDecimal("100"));

        // The ledger row must agree, because the void path reverses at the value stored on it.
        var movements = stockMovementRepo.findBySourceDocumentUidAndMovementType(
                gr.uid(), MovementType.GOODS_RECEIPT);
        assertThat(movements).hasSize(1);
        assertThat(movements.get(0).getUnitCostAmount())
                .as("stock_movements.unit_cost_amount is a per-base-unit cost")
                .isEqualByComparingTo(new BigDecimal("100"));
        assertThat(movements.get(0).getValueAmount())
                .isEqualByComparingTo(new BigDecimal("2400"));

        assertLedgerMatchesOnHand(product.id());
    }

    // Control: the base-unit path (purchase unit == base unit) must be unchanged by the K4 fix —
    // dividing the line total by the base quantity has to reduce to the unit cost exactly.
    @Test
    void receiveInBaseUnit_valuesStockAtTheUnitCost_unchanged() {
        ProductDto product = stockableProduct("BaseCost-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("10"), new BigDecimal("250"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("10")))));

        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));

        var soh = stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(
                        companyA.getId(), branchA.getId(), product.id())
                .orElseThrow(() -> new AssertionError("No on-hand row for the received product"));

        assertThat(soh.getQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(soh.getOnHandValue()).isEqualByComparingTo(new BigDecimal("2500"));
        assertThat(soh.getAvgCost()).isEqualByComparingTo(new BigDecimal("250"));
    }

    // =========================================================================
    // 13. K2 (Kilimanjaro 2026-08-08) — the GRN read model carries the values the
    //     printed goods-receipt note needs: the receipt total is computed HERE (the
    //     documents module is forbidden from deriving amounts — BR-DOC-02/BR-DOC-09).
    // =========================================================================

    @Test
    void goodsReceiptDto_carriesTheComputedReceiptTotalAndCurrency() {
        ProductDto product = stockableProduct("GrnPrint-Widget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("4"), new BigDecimal("175"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("4")))));

        assertThat(gr.receiptTotalAmount())
                .as("Σ line_cost_amount = 4 × 175")
                .isEqualByComparingTo(new BigDecimal("700"));
        assertThat(gr.currency()).isEqualTo("TZS");
        assertThat(gr.supplierName())
                .as("printed on the GRN in place of an internal supplier id")
                .isEqualTo("Test Supplier");
        assertThat(gr.lines().get(0).unitCostAmount()).isEqualByComparingTo(new BigDecimal("175"));
        assertThat(gr.lines().get(0).lineCostAmount()).isEqualByComparingTo(new BigDecimal("700"));

        // A re-read must produce the same figures (the printed document is re-rendered from live source).
        GoodsReceiptDto reread = grService.getByUid(gr.uid());
        assertThat(reread.receiptTotalAmount()).isEqualByComparingTo(new BigDecimal("700"));
    }

    // =========================================================================
    // 14. K3 (Kilimanjaro 2026-08-08) — direct goods receipt with no prior LPO:
    //     one call produces the backing PO AND the finalised receipt, atomically.
    // =========================================================================

    @Test
    void directReceipt_autoRaisesTheBackingPoAndReceivesIt() {
        ProductDto product = stockableProduct("Direct-Rice");

        GoodsReceiptDto gr = directGrService.receiveDirect(new DirectGoodsReceiptRequest(
                companyA.getUid(), supplierUid, "TZS", "Cash purchase, delivery note 4471",
                List.of(new DirectGoodsReceiptLineRequest(
                        product.uid(), pcsUid, new BigDecimal("40"), new BigDecimal("55"), null))));

        assertThat(gr.status()).isEqualTo(GoodsReceiptStatus.RECEIVED);
        assertThat(gr.receiptNumber()).isEqualTo("GRN-0001");
        assertThat(gr.purchaseOrderUid())
                .as("the receipt is anchored to the auto-raised PO — nothing downstream sees a special case")
                .isNotNull();
        assertThat(gr.receiptTotalAmount()).isEqualByComparingTo(new BigDecimal("2200"));

        // The backing PO exists, is numbered, and is fully received (nothing left outstanding).
        PurchaseOrderDto backing = poService.getByUid(gr.purchaseOrderUid());
        assertThat(backing.orderNumber()).isEqualTo("PO-0001");
        assertThat(backing.status()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(backing.origin())
                .as("V96: the synthesised order is labelled for ever after, so it can be told apart "
                        + "from an order a buyer actually raised")
                .isEqualTo(PurchaseOrderOrigin.DIRECT_RECEIPT);
        assertThat(backing.lines().get(0).outstandingQtyInBase()).isEqualByComparingTo(BigDecimal.ZERO);

        // The same STOCK.RECEIVED path as any other receipt: quantity AND value land correctly.
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));
        var soh = stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(
                        companyA.getId(), branchA.getId(), product.id())
                .orElseThrow(() -> new AssertionError("No on-hand row for the received product"));
        assertThat(soh.getQuantity()).isEqualByComparingTo(new BigDecimal("40"));
        assertThat(soh.getOnHandValue())
                .as("a direct receipt values stock at what was PAID — not at the current moving "
                        + "average, which is the whole reason the stock-adjustment workaround was wrong")
                .isEqualByComparingTo(new BigDecimal("2200"));
    }

    @Test
    void directReceipt_inAPackUnit_valuesStockAtTheBaseUnitCost() {
        ProductDto product = stockableProduct("Direct-Soap");
        UnitOfMeasureDto carton = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "CTN", "Carton of 12"));
        productService.addBulkPack(product.uid(),
                new CreateBulkPackRequest(carton.uid(), new BigDecimal("12")));

        directGrService.receiveDirect(new DirectGoodsReceiptRequest(
                companyA.getUid(), supplierUid, null, null,
                List.of(new DirectGoodsReceiptLineRequest(
                        product.uid(), carton.uid(), new BigDecimal("3"), new BigDecimal("1200"), null))));

        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));

        var soh = stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(
                        companyA.getId(), branchA.getId(), product.id())
                .orElseThrow(() -> new AssertionError("No on-hand row for the received product"));
        assertThat(soh.getQuantity()).isEqualByComparingTo(new BigDecimal("36"));
        assertThat(soh.getOnHandValue()).isEqualByComparingTo(new BigDecimal("3600"));
        assertThat(soh.getAvgCost()).isEqualByComparingTo(new BigDecimal("100"));
    }

    @Test
    void directReceipt_withNoLines_isRejectedWithAFriendlyMessage() {
        assertThatThrownBy(() -> directGrService.receiveDirect(new DirectGoodsReceiptRequest(
                companyA.getUid(), supplierUid, "TZS", null, List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Add at least one item to receive.");
    }

    @Test
    void directReceipt_withSupplierFromAnotherCompany_isRejectedAndWritesNothing() {
        Organisation orgD = organisations.save(new Organisation("Org D-Direct"));
        Company companyD  = companies.save(new Company(orgD, "DSUPP", "D Supplier Co"));
        Branch branchD    = branches.save(new Branch(companyD, "D-S1", "D Branch"));

        setContext(companyD, branchD);
        SupplierDto supplierD = supplierService.create(new CreateSupplierRequest(
                companyD.getId(), PartyType.INDIVIDUAL, "D-Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));

        setContext(companyA, branchA);
        ProductDto product = stockableProduct("Direct-CrossTenant");

        long posBefore = poRepo.count();
        long grsBefore = grRepo.count();

        assertThatThrownBy(() -> directGrService.receiveDirect(new DirectGoodsReceiptRequest(
                companyA.getUid(), supplierD.uid(), "TZS", null,
                List.of(new DirectGoodsReceiptLineRequest(
                        product.uid(), pcsUid, new BigDecimal("1"), new BigDecimal("10"), null)))))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class);

        assertThat(poRepo.count()).as("no phantom PO is left behind").isEqualTo(posBefore);
        assertThat(grRepo.count()).isEqualTo(grsBefore);
    }

    // -------------------------------------------------------------------------
    // 14b. K3 — spend approval on a direct receipt is DETECTIVE, not preventive.
    //
    // The defect this pins: DirectGoodsReceiptService creates the backing PO and places it inside
    // ONE method, so the PO does not exist until that call makes it. When PoApprovalGate demanded
    // pre-approval there was no moment at which anyone could approve — every direct receipt in an
    // approval-enabled company failed with a 409 and nobody had ever completed one. Owner decision
    // (2026-08-08): the goods are already on the dock, so record them and ratify afterwards.
    // -------------------------------------------------------------------------

    /** Turns the PO approval gate on for company A with the harshest possible threshold. */
    private void enablePoApprovalForEverySpend() {
        purchaseSettingsService.update(new UpdatePurchaseSettingsRequest(
                companyA.getUid(), true, BigDecimal.ZERO, "TZS",
                null, null, null, null, null, null, null, null));
    }

    @Test
    void directReceipt_completesEvenWhenEverySpendNeedsApproval() {
        enablePoApprovalForEverySpend();
        ProductDto product = stockableProduct("Direct-Approval");

        GoodsReceiptDto gr = directGrService.receiveDirect(new DirectGoodsReceiptRequest(
                companyA.getUid(), supplierUid, "TZS", null,
                List.of(new DirectGoodsReceiptLineRequest(
                        product.uid(), pcsUid, new BigDecimal("10"), new BigDecimal("500"), null))));

        assertThat(gr.status())
                .as("refusing goods that are already on the dock does not un-buy them — it only "
                        + "makes stock wrong, which is the worse failure")
                .isEqualTo(GoodsReceiptStatus.RECEIVED);

        // And the stock really moved — the whole point of accepting the receipt.
        dispatcher.dispatchOne(pendingEventId(DomainEventType.STOCK_RECEIVED));
        var soh = stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(
                        companyA.getId(), branchA.getId(), product.id())
                .orElseThrow(() -> new AssertionError("No on-hand row for the received product"));
        assertThat(soh.getQuantity()).isEqualByComparingTo(new BigDecimal("10"));
        assertThat(soh.getOnHandValue()).isEqualByComparingTo(new BigDecimal("5000"));
    }

    @Test
    void directReceipt_raisesARatificationRequestAgainstTheBackingOrder() {
        enablePoApprovalForEverySpend();
        ProductDto product = stockableProduct("Direct-Ratify");

        GoodsReceiptDto gr = directGrService.receiveDirect(new DirectGoodsReceiptRequest(
                companyA.getUid(), supplierUid, "TZS", null,
                List.of(new DirectGoodsReceiptLineRequest(
                        product.uid(), pcsUid, new BigDecimal("2"), new BigDecimal("750"), null))));

        var backing = poRepo.findByUid(gr.purchaseOrderUid()).orElseThrow();
        assertThat(backing.getApprovalRequestUid())
                .as("the spend still gets reviewed — through the SAME approvals engine and inbox a "
                        + "manager already uses, just after the event instead of before it")
                .isNotNull();
    }

    @Test
    void theApprovalExemptionDoesNotLeakToAnOrderABuyerRaised() {
        enablePoApprovalForEverySpend();
        ProductDto product = stockableProduct("Manual-StillGated");
        PurchaseOrderDto draft = createDraftWithLine(product.uid(), new BigDecimal("5"),
                new BigDecimal("100"));

        assertThatThrownBy(() -> poService.placeOrder(draft.uid()))
                .as("the preventive control is untouched wherever pre-approval is actually possible")
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("requires approval");
    }

    // =========================================================================
    // 15. K3 / V96 — provenance keeps the synthesised orders out of the buyer's list.
    // =========================================================================

    @Test
    void aNormallyRaisedPurchaseOrderIsManualAndStaysInTheDefaultList() {
        ProductDto product = stockableProduct("Origin-Manual");
        PurchaseOrderDto po = createDraftWithLine(product.uid(), new BigDecimal("5"),
                new BigDecimal("100"));

        assertThat(po.origin())
                .as("nothing about the normal PO screen changed — MANUAL is the default")
                .isEqualTo(PurchaseOrderOrigin.MANUAL);

        assertThat(poService.list(companyA.getId(), null, false, Pageable.unpaged()).getContent())
                .extracting(PurchaseOrderDto::uid)
                .contains(po.uid());
    }

    @Test
    void placingAndReceivingANormalOrderNeverChangesItsProvenance() {
        // Provenance is set once at create; no lifecycle transition may quietly relabel an order.
        ProductDto product = stockableProduct("Origin-Lifecycle");
        PurchaseOrderDto placed = placeOrderWithLine(product.uid(), new BigDecimal("6"),
                new BigDecimal("30"));

        grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), null,
                List.of(new GoodsReceiptLineRequest(placed.lines().get(0).uid(),
                        new BigDecimal("6")))));

        assertThat(poRepo.findByUid(placed.uid()).orElseThrow().getOrigin())
                .isEqualTo(PurchaseOrderOrigin.MANUAL);
        assertThat(poService.list(companyA.getId(), null, false, Pageable.unpaged()).getContent())
                .extracting(PurchaseOrderDto::uid)
                .as("a received order is still the buyer's order and stays on their list")
                .contains(placed.uid());
    }

    @Test
    void theDirectReceiptsBackingOrderIsHiddenFromTheDefaultListButAvailableOnOptIn() {
        ProductDto product = stockableProduct("Origin-Hidden");
        PurchaseOrderDto buyersOrder = createDraftWithLine(product.uid(), new BigDecimal("5"),
                new BigDecimal("100"));

        GoodsReceiptDto gr = directGrService.receiveDirect(new DirectGoodsReceiptRequest(
                companyA.getUid(), supplierUid, "TZS", null,
                List.of(new DirectGoodsReceiptLineRequest(
                        product.uid(), pcsUid, new BigDecimal("4"), new BigDecimal("60"), null))));
        String syntheticUid = gr.purchaseOrderUid();

        // Default: the buyer sees their own order and nothing else.
        assertThat(poService.list(companyA.getId(), null, false, Pageable.unpaged()).getContent())
                .extracting(PurchaseOrderDto::uid)
                .as("a delivery per day would otherwise bury the buyer's real orders")
                .contains(buyersOrder.uid())
                .doesNotContain(syntheticUid);

        // Opt-in: everything, badged with its provenance.
        assertThat(poService.list(companyA.getId(), null, true, Pageable.unpaged()).getContent())
                .extracting(PurchaseOrderDto::uid)
                .contains(buyersOrder.uid(), syntheticUid);

        // The synthesised order is never unreachable — its own uid still resolves.
        assertThat(poService.getByUid(syntheticUid).origin())
                .isEqualTo(PurchaseOrderOrigin.DIRECT_RECEIPT);
    }

    @Test
    void searchingBySupplierDoesNotLeakTheHiddenBackingOrder() {
        // Both orders are for the same supplier, so a name search would surface both if the
        // search path forgot the filter that the browse path applies.
        ProductDto product = stockableProduct("Origin-Search");
        PurchaseOrderDto buyersOrder = createDraftWithLine(product.uid(), new BigDecimal("5"),
                new BigDecimal("100"));

        GoodsReceiptDto gr = directGrService.receiveDirect(new DirectGoodsReceiptRequest(
                companyA.getUid(), supplierUid, "TZS", null,
                List.of(new DirectGoodsReceiptLineRequest(
                        product.uid(), pcsUid, new BigDecimal("1"), new BigDecimal("10"), null))));

        assertThat(poService.list(companyA.getId(), "Test Supplier", false, Pageable.unpaged())
                .getContent())
                .extracting(PurchaseOrderDto::uid)
                .contains(buyersOrder.uid())
                .doesNotContain(gr.purchaseOrderUid());

        assertThat(poService.list(companyA.getId(), "Test Supplier", true, Pageable.unpaged())
                .getContent())
                .extracting(PurchaseOrderDto::uid)
                .contains(buyersOrder.uid(), gr.purchaseOrderUid());
    }

    // =========================================================================
    // findApprovalSnapshots — the read seam AP's bill list uses (K3 follow-up)
    // =========================================================================

    @Test
    void findApprovalSnapshots_returnsOriginAndApprovalStatusForAWholePageInOneQuery() {
        // Pins the JPQL projection against a real database (a mocked repository would prove
        // nothing about the constructor expression) and the contract AP depends on: the two facts
        // it needs, for every order on a page, with no approval-engine poll and no write.
        ProductDto product = stockableProduct("Snapshot-Batch");
        PurchaseOrderDto buyersOrder = createDraftWithLine(product.uid(), new BigDecimal("5"),
                new BigDecimal("100"));

        GoodsReceiptDto gr = directGrService.receiveDirect(new DirectGoodsReceiptRequest(
                companyA.getUid(), supplierUid, "TZS", null,
                List.of(new DirectGoodsReceiptLineRequest(
                        product.uid(), pcsUid, new BigDecimal("3"), new BigDecimal("70"), null))));
        String directUid = gr.purchaseOrderUid();

        Map<String, PurchaseOrderApprovalSnapshotDto> snapshots = poService.findApprovalSnapshots(
                Arrays.asList(directUid, buyersOrder.uid(), directUid, null, "   ", "NO-SUCH-ORDER"));

        // Duplicates collapse; blanks, nulls and unknown uids are simply absent.
        assertThat(snapshots).containsOnlyKeys(directUid, buyersOrder.uid());

        PurchaseOrderApprovalSnapshotDto direct = snapshots.get(directUid);
        assertThat(direct.origin()).isEqualTo(PurchaseOrderOrigin.DIRECT_RECEIPT);
        assertThat(direct.companyId()).isEqualTo(companyA.getId());
        assertThat(direct.approvalStatus())
                .as("a direct receipt always carries a post-hoc ratification request")
                .isIn(PoApprovalStatus.PENDING, PoApprovalStatus.APPROVED);
        assertThat(snapshots.get(buyersOrder.uid()).origin())
                .isEqualTo(PurchaseOrderOrigin.MANUAL);

        // The cheap read and the detail read must never disagree about the same order.
        assertThat(direct.approvalStatus().name())
                .isEqualTo(poService.getByUid(directUid).approvalStatus());
    }

    @Test
    void findApprovalSnapshots_leavesOutOrdersTheCallerMayNotSee() {
        // Tenancy is scoped from the LOADED row. Company B's order is dropped, not refused: this
        // feeds a listing, and one stray reference must never take a whole page down.
        ProductDto product = stockableProduct("Snapshot-Tenant");
        PurchaseOrderDto ownOrder = createDraftWithLine(product.uid(), new BigDecimal("2"),
                new BigDecimal("50"));

        Organisation orgB = organisations.save(new Organisation("Snapshot Org B"));
        Company companyB = companies.save(new Company(orgB, "PURCB", "Purch IT Co B"));
        Branch branchB = branches.save(new Branch(companyB, "PURCH-B1", "Purch IT Branch B1"));
        String foreignUid = createForeignOrder(companyB, branchB);

        // A NON-root clerk in company A: root short-circuits every scope check, so testing this as
        // root would prove nothing (the RBAC lesson from the tenant-isolation sweep).
        RequestContext.set(new RequestContext.Principal(
                rootId, "purch_clerk", false, companyA.getId(), branchA.getId(), null));

        Map<String, PurchaseOrderApprovalSnapshotDto> snapshots =
                poService.findApprovalSnapshots(List.of(ownOrder.uid(), foreignUid));

        assertThat(snapshots).containsOnlyKeys(ownOrder.uid());
    }

    /** Creates a PO in another company, from that company's own context. */
    private String createForeignOrder(Company company, Branch branch) {
        setContext(company, branch);
        SupplierDto supplierB = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "Other Co Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));
        return poService.create(new CreatePurchaseOrderRequest(
                company.getUid(), supplierB.uid(), "TZS", null, null, List.of())).uid();
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
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null));
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
