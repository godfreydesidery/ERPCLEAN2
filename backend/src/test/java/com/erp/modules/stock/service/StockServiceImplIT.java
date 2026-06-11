package com.erp.modules.stock.service;

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
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.products.domain.dto.AddComponentRequest;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.PriceListDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
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
import com.erp.modules.stock.domain.dto.StockMovementDto;
import com.erp.modules.stock.domain.dto.StockOnHandDto;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.dto.StockReceiptVoidedPayload;
import com.erp.modules.stock.domain.enums.AdjustmentReason;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.audit.AuditLog;
import com.erp.platform.audit.AuditRepository;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Integration tests for the Stock module (ADR-0010 Increment 2).
 *
 * <p>Covers:
 * <ol>
 *   <li>Sale→stock loop: finalise → SALE.FINALISED → dispatch → SALE_ISSUE + on-hand deducted.</li>
 *   <li>Void loop: void → SALE.VOIDED → dispatch → SALE_REVERSAL + on-hand restored.</li>
 *   <li>Recipe explosion: composed product → component SALE_ISSUE rows; composed on-hand untouched.</li>
 *   <li>Non-stockable component: skipped in explosion (no movement row).</li>
 *   <li>Overselling: on-hand goes negative; no error (BR-STOCK-03).</li>
 *   <li>Idempotency: same event dispatched twice → deducted once (NFR-STOCK-03).</li>
 *   <li>GOODS_RECEIPT / GOODS_RECEIPT_REVERSAL via synthetic events.</li>
 *   <li>Manual opening-balance: sets on-hand; second opening-balance rejected.</li>
 *   <li>Adjustment +/- with reason; zero-quantity rejected.</li>
 *   <li>Cross-tenant read blocked (company A cannot see company B's stock).</li>
 *   <li>Audit: manual adjustment writes audit_logs; event-driven movement does NOT.</li>
 *   <li>BR-STOCK-01: on-hand == Σ movements after every operation.</li>
 * </ol>
 *
 * <p>The dispatcher is driven synchronously via {@link DomainEventDispatcher#dispatchOne(Long)} —
 * no sleeps, fully deterministic.
 */
class StockServiceImplIT extends PostgresIntegrationTest {

    @Autowired private StockService            stockService;
    @Autowired private SalesInvoiceService     salesInvoiceService;
    @Autowired private TaxRateSeeder           taxRateSeeder;
    @Autowired private CustomerService         customerService;
    @Autowired private AgentService            agentService;
    @Autowired private ProductService          productService;
    @Autowired private PriceListService        priceListService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private StockOnHandRepository   stockOnHandRepo;
    @Autowired private StockMovementRepository stockMovementRepo;
    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private AuditRepository         auditRepository;
    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;
    @Autowired private TransactionTemplate     txTemplate;

    private Company companyA;
    private Branch  branchA;
    private Long    rootId;

    private String pcsUid;
    private String priceListUid;
    private String customerUid;
    private String agentUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Stock IT Org"));
        companyA = companies.save(new Company(org, "STKCA", "Stock IT Co A"));
        branchA  = branches.save(new Branch(companyA, "STK-A1", "Stock IT Branch A1"));

        AppUser root = new AppUser("stk_root", passwordEncoder.encode("RootPass1!"), "STK Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        setContext(companyA, branchA);

        taxRateSeeder.seedDefaults(companyA.getId());

        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));
        pcsUid = pcs.uid();

        PriceListDto pl = priceListService.create(
                new CreatePriceListRequest(companyA.getUid(), "RETAIL", "Retail"));
        priceListUid = pl.uid();

        CustomerDto cust = customerService.create(new CreateCustomerRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "STK Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null));
        customerUid = cust.uid();

        AgentDto ag = agentService.create(new CreateAgentRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "STK Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null));
        agentUid = ag.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // 1. Sale → stock loop: SALE.FINALISED → SALE_ISSUE
    // =========================================================================

    @Test
    void finalise_dispatchSaleFinalised_deductsOnHand() {
        ProductDto product = stockableProduct("Widget");

        SalesInvoiceDto draft = makeDraftWithLine(product.uid(), BigDecimal.valueOf(3));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());

        dispatcher.dispatchOne(pendingEventId(DomainEventType.SALE_FINALISED));

        // on-hand: 0 − 3 = −3
        StockOnHandDto soh = onHand(product.id());
        assertThat(soh.quantity()).isEqualByComparingTo(new BigDecimal("-3"));

        // exactly one SALE_ISSUE movement
        List<StockMovementDto> issued = issuedFor(draft.uid());
        assertThat(issued).hasSize(1);
        assertThat(issued.get(0).quantity()).isEqualByComparingTo(new BigDecimal("-3"));

        assertLedgerMatchesOnHand(product.id());
    }

    // =========================================================================
    // 2. Void loop: SALE.VOIDED → SALE_REVERSAL, on-hand restored
    // =========================================================================

    @Test
    void void_dispatchSaleVoided_restoresOnHand() {
        ProductDto product = stockableProduct("VoidWidget");

        SalesInvoiceDto draft = makeDraftWithLine(product.uid(), BigDecimal.valueOf(5));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEventId(DomainEventType.SALE_FINALISED));

        salesInvoiceService.voidInvoice(draft.uid(), new VoidInvoiceRequest("test void"));
        dispatcher.dispatchOne(pendingEventId(DomainEventType.SALE_VOIDED));

        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(BigDecimal.ZERO);

        List<StockMovementDto> reversals = movementsFor(draft.uid(), MovementType.SALE_REVERSAL);
        assertThat(reversals).hasSize(1);
        assertThat(reversals.get(0).quantity()).isEqualByComparingTo(new BigDecimal("5"));

        assertLedgerMatchesOnHand(product.id());
    }

    // =========================================================================
    // 3. Recipe explosion: composed product → component SALE_ISSUE rows
    // =========================================================================

    @Test
    void finalise_composedProduct_explodesComponentIssues() {
        // comp1 ×2, comp2 ×3 per composed unit
        ProductDto comp1    = stockableProduct("Comp1");
        ProductDto comp2    = stockableProduct("Comp2");
        ProductDto composed = stockableProduct("Composed");

        productService.addComponent(composed.uid(),
                new AddComponentRequest(comp1.uid(), new BigDecimal("2")));
        productService.addComponent(composed.uid(),
                new AddComponentRequest(comp2.uid(), new BigDecimal("3")));

        // Sell 4 units of composed → comp1 deducted 4×2=8, comp2 4×3=12
        SalesInvoiceDto draft = makeDraftWithLine(composed.uid(), BigDecimal.valueOf(4));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEventId(DomainEventType.SALE_FINALISED));

        // Composed product: NO on-hand row or movement
        assertThat(stockOnHandRepo.findByCompanyIdAndBranchIdAndProductId(
                companyA.getId(), branchA.getId(), composed.id())).isEmpty();

        // comp1: −8
        assertThat(onHand(comp1.id()).quantity()).isEqualByComparingTo(new BigDecimal("-8"));
        // comp2: −12
        assertThat(onHand(comp2.id()).quantity()).isEqualByComparingTo(new BigDecimal("-12"));

        // exactly 2 SALE_ISSUE rows (one per stockable component)
        assertThat(issuedFor(draft.uid())).hasSize(2);

        assertLedgerMatchesOnHand(comp1.id());
        assertLedgerMatchesOnHand(comp2.id());
    }

    // =========================================================================
    // 4. Non-stockable component is skipped — no movement row
    // =========================================================================

    @Test
    void finalise_composedWithNonStockableComponent_skipsNonStockable() {
        ProductDto stockableComp    = stockableProduct("StockComp");
        ProductDto nonStockableComp = nonStockableProduct("ServiceComp");
        ProductDto composed         = stockableProduct("ComposedMixed");

        productService.addComponent(composed.uid(),
                new AddComponentRequest(stockableComp.uid(), BigDecimal.ONE));
        productService.addComponent(composed.uid(),
                new AddComponentRequest(nonStockableComp.uid(), BigDecimal.ONE));

        SalesInvoiceDto draft = makeDraftWithLine(composed.uid(), BigDecimal.valueOf(2));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEventId(DomainEventType.SALE_FINALISED));

        // Only 1 SALE_ISSUE — the stockable component
        List<StockMovementDto> issued = issuedFor(draft.uid());
        assertThat(issued).hasSize(1);
        assertThat(issued.get(0).productId()).isEqualTo(stockableComp.id());

        // Non-stockable: no on-hand, no movement
        assertThat(stockOnHandRepo.findByCompanyIdAndBranchIdAndProductId(
                companyA.getId(), branchA.getId(), nonStockableComp.id())).isEmpty();
    }

    // =========================================================================
    // 5. Overselling: on-hand goes negative — no error (BR-STOCK-03)
    // =========================================================================

    @Test
    void finalise_moreQtyThanOnHand_onHandGoesNegative() {
        ProductDto product = stockableProduct("OversellWidget");

        // No opening balance — on-hand starts at 0
        SalesInvoiceDto draft = makeDraftWithLine(product.uid(), BigDecimal.valueOf(10));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(pendingEventId(DomainEventType.SALE_FINALISED));

        StockOnHandDto soh = onHand(product.id());
        assertThat(soh.quantity()).isEqualByComparingTo(new BigDecimal("-10"));
        assertThat(soh.negative()).isTrue();
    }

    // =========================================================================
    // 6. Idempotency: same SALE.FINALISED dispatched twice → deducted once
    // =========================================================================

    @Test
    void dispatchSaleFinalised_twiceForSameEvent_deductedOnce() {
        ProductDto product = stockableProduct("IdempWidget");

        SalesInvoiceDto draft = makeDraftWithLine(product.uid(), BigDecimal.valueOf(2));
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());

        Long eventId = pendingEventId(DomainEventType.SALE_FINALISED);

        // First dispatch: succeeds, marks DISPATCHED, writes processed_events
        dispatcher.dispatchOne(eventId);

        // Second dispatch of the same id: status is DISPATCHED → dispatcher skips early
        dispatcher.dispatchOne(eventId);

        // On-hand must be −2 (deducted exactly once)
        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(new BigDecimal("-2"));
        assertThat(issuedFor(draft.uid())).hasSize(1);
    }

    // =========================================================================
    // 7. GOODS_RECEIPT: synthetic event increases on-hand
    // =========================================================================

    @Test
    void stockReceived_syntheticEvent_increasesOnHand() {
        ProductDto product = stockableProduct("ReceivedWidget");
        String receiptUid = "RCPT-SYNTH-001";

        StockReceivedPayload payload = new StockReceivedPayload(
                receiptUid, companyA.getId(), branchA.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(
                        product.id(), product.uid(), null, new BigDecimal("20"))));

        Long eventId = publishInTx(DomainEventType.STOCK_RECEIVED,
                DomainEventType.AGG_GOODS_RECEIPT, 1L, receiptUid, payload);
        dispatcher.dispatchOne(eventId);

        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(new BigDecimal("20"));
        assertLedgerMatchesOnHand(product.id());
    }

    // =========================================================================
    // 8. GOODS_RECEIPT_REVERSAL: voids the receipt, on-hand returns to zero
    // =========================================================================

    @Test
    void stockReceiptVoided_syntheticEvent_reversesOnHand() {
        ProductDto product = stockableProduct("VoidRcptWidget");
        String receiptUid = "RCPT-VOID-001";

        // Receive first
        StockReceivedPayload recPayload = new StockReceivedPayload(
                receiptUid, companyA.getId(), branchA.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(
                        product.id(), product.uid(), null, new BigDecimal("15"))));
        Long recId = publishInTx(DomainEventType.STOCK_RECEIVED,
                DomainEventType.AGG_GOODS_RECEIPT, 2L, receiptUid, recPayload);
        dispatcher.dispatchOne(recId);

        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(new BigDecimal("15"));

        // Then void
        StockReceiptVoidedPayload voidPayload = new StockReceiptVoidedPayload(
                receiptUid, companyA.getId(), branchA.getId(),
                List.of(new StockReceiptVoidedPayload.LineItem(
                        product.id(), product.uid(), null, new BigDecimal("15"))));
        Long voidId = publishInTx(DomainEventType.STOCK_RECEIPT_VOIDED,
                DomainEventType.AGG_GOODS_RECEIPT, 2L, receiptUid, voidPayload);
        dispatcher.dispatchOne(voidId);

        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(BigDecimal.ZERO);
        assertLedgerMatchesOnHand(product.id());
    }

    // =========================================================================
    // 9. Manual opening-balance: sets on-hand; second rejected
    // =========================================================================

    @Test
    void openingBalance_setsOnHand_secondRejected() {
        ProductDto product = stockableProduct("OBWidget");

        StockMovementDto mov = stockService.openingBalance(
                new OpeningBalanceRequest(product.uid(), new BigDecimal("100"), "initial"));

        assertThat(mov.movementType()).isEqualTo(MovementType.OPENING_BALANCE);
        assertThat(mov.quantity()).isEqualByComparingTo(new BigDecimal("100"));
        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(new BigDecimal("100"));

        assertThatThrownBy(() -> stockService.openingBalance(
                new OpeningBalanceRequest(product.uid(), new BigDecimal("50"), "second")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("already exists");
    }

    // =========================================================================
    // 10. Adjustment +/- with reason; zero-quantity rejected
    // =========================================================================

    @Test
    void adjustment_positiveAndNegative_updatesOnHand() {
        ProductDto product = stockableProduct("AdjWidget");
        stockService.openingBalance(new OpeningBalanceRequest(product.uid(), new BigDecimal("50"), null));

        stockService.adjust(new AdjustStockRequest(
                product.uid(), new BigDecimal("10"), AdjustmentReason.COUNT_CORRECTION, null,
                null, null));
        stockService.adjust(new AdjustStockRequest(
                product.uid(), new BigDecimal("-5"), AdjustmentReason.DAMAGE, "broken",
                null, null));

        assertThat(onHand(product.id()).quantity()).isEqualByComparingTo(new BigDecimal("55")); // 50+10−5
        assertLedgerMatchesOnHand(product.id());
    }

    @Test
    void adjustment_withZeroQuantity_rejected() {
        ProductDto product = stockableProduct("ZeroWidget");

        assertThatThrownBy(() -> stockService.adjust(
                new AdjustStockRequest(product.uid(), BigDecimal.ZERO,
                        AdjustmentReason.COUNT_CORRECTION, null, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-zero");
    }

    // =========================================================================
    // 11. Cross-tenant read: company B cannot see company A's stock
    // =========================================================================

    @Test
    void listOnHand_differentTenant_seesOnlyOwnScope() {
        // Put stock in company A
        ProductDto productA = stockableProduct("TenantWidget");
        stockService.openingBalance(
                new OpeningBalanceRequest(productA.uid(), BigDecimal.TEN, null));

        // Switch to company B
        Organisation org2 = organisations.save(new Organisation("Org B"));
        Company companyB  = companies.save(new Company(org2, "STKB", "Stock IT Co B"));
        Branch branchB    = branches.save(new Branch(companyB, "STK-B1", "Branch B1"));

        RequestContext.set(new RequestContext.Principal(
                rootId, "stk_root", false, companyB.getId(), branchB.getId(), null));

        // Company B's list returns empty (its own branch has nothing)
        Page<StockOnHandDto> bPage = stockService.listOnHand(Pageable.unpaged());
        assertThat(bPage.getContent()).isEmpty();

        // Non-root user in B must not be able to act in A's scope
        assertThatThrownBy(() -> {
            RequestContext.set(new RequestContext.Principal(
                    rootId, "stk_root", false, companyB.getId(), branchB.getId(), null));
            stockService.listMovements(productA.uid(), Pageable.unpaged());
        }).isInstanceOf(Exception.class); // ForbiddenException: productA belongs to A
    }

    // =========================================================================
    // 12. Audit: manual adjustment writes audit_logs; event-driven does NOT
    // =========================================================================

    @Test
    void adjustment_writesAuditLog() {
        ProductDto product = stockableProduct("AuditWidget");
        long auditBefore = auditRepository.count();

        stockService.adjust(new AdjustStockRequest(
                product.uid(), new BigDecimal("7"), AdjustmentReason.COUNT_CORRECTION, null,
                null, null));

        List<AuditLog> logs = auditRepository.findAll();
        assertThat(logs.size()).isGreaterThan((int) auditBefore);
        assertThat(logs).anyMatch(l -> "STOCK.ADJUST".equals(l.getAction()));
    }

    @Test
    void eventDrivenSaleIssue_doesNotWriteAuditLog() {
        ProductDto product = stockableProduct("NoAuditWidget");

        SalesInvoiceDto draft = makeDraftWithLine(product.uid(), BigDecimal.ONE);
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        long auditAfterFinalise = auditRepository.count(); // SALES.INVOICE.FINALISE row already written

        dispatcher.dispatchOne(pendingEventId(DomainEventType.SALE_FINALISED));

        // Dispatching SALE.FINALISED must not add any new audit_logs rows (ADR-0010 D-12)
        assertThat(auditRepository.count()).isEqualTo(auditAfterFinalise);
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void setContext(Company company, Branch branch) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "stk_root", true, company.getId(), branch.getId(), null));
    }

    private ProductDto stockableProduct(String name) {
        ProductDto p = productService.create(new CreateProductRequest(
                companyA.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD));
        productService.setPrice(p.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto("500", "TZS")));
        return p;
    }

    private ProductDto nonStockableProduct(String name) {
        ProductDto p = productService.create(new CreateProductRequest(
                companyA.getUid(), null, name, null,
                ProductType.SERVICE, true, false, pcsUid, null, VatStatus.STANDARD));
        productService.setPrice(p.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto("100", "TZS")));
        return p;
    }

    private SalesInvoiceDto makeDraftWithLine(String productUid, BigDecimal qty) {
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                companyA.getUid(), customerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, qty, null, null));
        // 500 TZS × qty × 1.18 VAT
        BigDecimal gross = qty.multiply(new BigDecimal("590"));
        salesInvoiceService.addPayment(draft.uid(),
                new AddPaymentRequest(TenderType.CASH, gross, "TZS", null));
        return draft;
    }

    private Long pendingEventId(String eventType) {
        return domainEventRepo.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> com.erp.platform.events.DomainEventStatus.PENDING == e.getStatus())
                .reduce((a, b) -> b) // last inserted
                .map(DomainEvent::getId)
                .orElseThrow(() -> new AssertionError("No PENDING event of type: " + eventType));
    }

    /** Publish an event inside its own transaction and return the new event's id. */
    private Long publishInTx(String eventType, String aggType, Long aggId,
                              String aggUid, Object payload) {
        txTemplate.execute(s -> {
            outboxPublisher.publish(eventType, aggType, aggId, aggUid,
                    companyA.getId(), branchA.getId(), payload);
            return null;
        });
        return pendingEventId(eventType);
    }

    private StockOnHandDto onHand(Long productId) {
        return stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(companyA.getId(), branchA.getId(), productId)
                .map(StockOnHandDto::from)
                .orElseThrow(() -> new AssertionError("No on-hand row for productId=" + productId));
    }

    private List<StockMovementDto> issuedFor(String invoiceUid) {
        return movementsFor(invoiceUid, MovementType.SALE_ISSUE);
    }

    private List<StockMovementDto> movementsFor(String docUid, MovementType type) {
        return stockMovementRepo.findBySourceDocumentUidAndMovementType(docUid, type)
                .stream().map(StockMovementDto::from).toList();
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
