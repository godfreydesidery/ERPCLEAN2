package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.stock.domain.dto.CreateVanReconciliationRequest;
import com.erp.modules.stock.domain.dto.EnterVanReconciliationLinesRequest;
import com.erp.modules.stock.domain.dto.VanMovementSumRowDto;
import com.erp.modules.stock.domain.dto.VanReconciliationDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.entity.VanReconciliation;
import com.erp.modules.stock.domain.entity.VanReconciliationLine;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.domain.enums.VanReconciliationStatus;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.modules.stock.repository.VanReconciliationLineRepository;
import com.erp.modules.stock.repository.VanReconciliationRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Unit tests for {@link VanReconciliationServiceImpl} (ADR-0051 D-8).
 *
 * <p>Covers: derivation (loaded = Σ TRANSFER_IN, returned = −Σ TRANSFER_OUT), expected/variance
 * computation, lifecycle guards, and the RECORD-ONLY invariant (D-8.2) — reconcile() never touches
 * a stock-posting collaborator because {@link VanReconciliationServiceImpl} is not given one.
 *
 * <p>The van/agent-assignment guards ("one active van per agent", "agent must be same company")
 * are tested in {@code StockLocationServiceImplTest} — that is where the assignment happens
 * (ADR-0051 D-8.4, the existing location-management endpoint), not here.
 */
class VanReconciliationServiceImplTest {

    private static final Long COMPANY_ID       = 10L;
    private static final Long BRANCH_ID        = 20L;
    private static final Long USER_ID          = 1L;
    private static final Long VAN_LOCATION_ID  = 30L;
    private static final Long PRODUCT_ID       = 99L;

    private VanReconciliationRepository     reconciliations;
    private VanReconciliationLineRepository lineRepo;
    private StockMovementRepository         movements;
    private StockOnHandRepository           onHands;
    private StockLocationRepository         stockLocations;
    private LocationResolver                locationResolver;
    private ProductService                  productService;
    private AgentService                    agentService;
    private WarehouseNumberGenerator        numberGenerator;
    private OutboxPublisher                 outbox;
    private ScopeGuard                      scopeGuard;
    private AuditService                    audit;

    private VanReconciliationServiceImpl service;

    @BeforeEach
    void setUp() {
        reconciliations  = mock(VanReconciliationRepository.class);
        lineRepo         = mock(VanReconciliationLineRepository.class);
        movements        = mock(StockMovementRepository.class);
        onHands          = mock(StockOnHandRepository.class);
        stockLocations   = mock(StockLocationRepository.class);
        locationResolver = mock(LocationResolver.class);
        productService   = mock(ProductService.class);
        agentService     = mock(AgentService.class);
        numberGenerator  = mock(WarehouseNumberGenerator.class);
        outbox           = mock(OutboxPublisher.class);
        scopeGuard       = mock(ScopeGuard.class);
        audit            = mock(AuditService.class);

        service = new VanReconciliationServiceImpl(reconciliations, lineRepo, movements, onHands,
                stockLocations, locationResolver, productService, agentService, numberGenerator,
                outbox, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(
                USER_ID, "agent@test.com", false, COMPANY_ID, BRANCH_ID, null));

        when(reconciliations.save(any(VanReconciliation.class))).thenAnswer(inv -> {
            VanReconciliation r = inv.getArgument(0);
            setId(r, 100L);
            setUid(r, "VR-UID-0001");
            return r;
        });
        when(lineRepo.save(any(VanReconciliationLine.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // create(): derivation (loaded = Σ TRANSFER_IN, returned = −Σ TRANSFER_OUT) + expected
    // -------------------------------------------------------------------------

    @Test
    void create_derivesLoadedAndReturned_fromTransferMovements_computesExpected() {
        StockLocation van = vanLocation(null);
        when(locationResolver.resolveLocation("VAN-UID-001", COMPANY_ID)).thenReturn(van);
        when(numberGenerator.nextVanRecon(COMPANY_ID)).thenReturn("VR-0001");
        when(reconciliations.existsByVanLocationIdAndBusinessDateAndStatusNot(
                eq(VAN_LOCATION_ID), any(LocalDate.class), eq(VanReconciliationStatus.CANCELLED)))
                .thenReturn(false);

        when(movements.sumByLocationTypeAndWindow(eq(COMPANY_ID), eq(BRANCH_ID), eq(VAN_LOCATION_ID),
                eq(MovementType.TRANSFER_IN), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(new VanMovementSumRowDto(PRODUCT_ID, new BigDecimal("100"))));
        // TRANSFER_OUT rows are signed negative on the ledger: -25 sums to -25.
        // returned_qty = -Σ(TRANSFER_OUT) = -(-25) = 25.
        when(movements.sumByLocationTypeAndWindow(eq(COMPANY_ID), eq(BRANCH_ID), eq(VAN_LOCATION_ID),
                eq(MovementType.TRANSFER_OUT), any(Instant.class), any(Instant.class)))
                .thenReturn(List.of(new VanMovementSumRowDto(PRODUCT_ID, new BigDecimal("-25"))));

        when(productService.getById(PRODUCT_ID)).thenReturn(productDto());
        StockOnHand soh = onHand(new BigDecimal("12.5000"));
        when(onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                COMPANY_ID, BRANCH_ID, VAN_LOCATION_ID, PRODUCT_ID))
                .thenReturn(Optional.of(soh));

        CreateVanReconciliationRequest req = new CreateVanReconciliationRequest(
                "VAN-UID-001", LocalDate.of(2026, 7, 4), "Day-end", null);

        VanReconciliationDto dto = service.create(req);

        assertThat(dto.reconNumber()).isEqualTo("VR-0001");
        assertThat(dto.status()).isEqualTo(VanReconciliationStatus.OPEN);

        ArgumentCaptor<VanReconciliationLine> lineCaptor =
                ArgumentCaptor.forClass(VanReconciliationLine.class);
        verify(lineRepo).save(lineCaptor.capture());
        VanReconciliationLine line = lineCaptor.getValue();
        assertThat(line.getLoadedQty()).isEqualByComparingTo("100");
        assertThat(line.getReturnedQty()).isEqualByComparingTo("25");
        assertThat(line.getSoldQty()).isEqualByComparingTo(BigDecimal.ZERO);
        // expected = loaded - sold - returned = 100 - 0 - 25 = 75
        assertThat(line.getExpectedQty()).isEqualByComparingTo("75");
        assertThat(line.getUnitCostAmount()).isEqualByComparingTo("12.5000");
    }

    @Test
    void create_nonVanLocation_rejected() {
        StockLocation warehouse = mock(StockLocation.class);
        when(warehouse.getLocationType()).thenReturn(LocationType.WAREHOUSE);
        when(locationResolver.resolveLocation("WH-UID", COMPANY_ID)).thenReturn(warehouse);

        CreateVanReconciliationRequest req = new CreateVanReconciliationRequest(
                "WH-UID", LocalDate.now(), null, null);

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(IllegalArgumentException.class);
        verify(reconciliations, never()).save(any());
    }

    @Test
    void create_duplicateLiveWorksheetForSameVanAndDate_rejected() {
        StockLocation van = vanLocation(null);
        when(locationResolver.resolveLocation("VAN-UID-001", COMPANY_ID)).thenReturn(van);
        when(reconciliations.existsByVanLocationIdAndBusinessDateAndStatusNot(
                eq(VAN_LOCATION_ID), any(LocalDate.class), eq(VanReconciliationStatus.CANCELLED)))
                .thenReturn(true);

        CreateVanReconciliationRequest req = new CreateVanReconciliationRequest(
                "VAN-UID-001", LocalDate.now(), null, null);

        assertThatThrownBy(() -> service.create(req)).isInstanceOf(ConflictException.class);
        verify(reconciliations, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // enterLines(): sold + physical -> expected/variance recompute
    // -------------------------------------------------------------------------

    @Test
    void enterLines_computesExpectedAndVariance() {
        VanReconciliation recon = openRecon();
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        VanReconciliationLine line = newLine(recon, 1L, PRODUCT_ID,
                new BigDecimal("100"), new BigDecimal("25")); // loaded=100 returned=25
        when(lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId()))
                .thenReturn(List.of(line));

        EnterVanReconciliationLinesRequest req = new EnterVanReconciliationLinesRequest(List.of(
                new EnterVanReconciliationLinesRequest.LineEntry(
                        1L, null, new BigDecimal("70"), new BigDecimal("3"), null, null)));

        service.enterLines("VR-UID-1", req);

        // expected = 100 - 70 - 25 = 5 ; variance = physical(3) - expected(5) = -2
        assertThat(line.getExpectedQty()).isEqualByComparingTo("5");
        assertThat(line.getVarianceQty()).isEqualByComparingTo("-2");
    }

    @Test
    void enterLines_resolvesByProductUid_whenLineIdNotSupplied() {
        VanReconciliation recon = openRecon();
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        VanReconciliationLine line = newLine(recon, 1L, PRODUCT_ID,
                new BigDecimal("50"), BigDecimal.ZERO);
        when(lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId()))
                .thenReturn(List.of(line));
        when(productService.getByUid("PROD-UID-001")).thenReturn(productDto());

        EnterVanReconciliationLinesRequest req = new EnterVanReconciliationLinesRequest(List.of(
                new EnterVanReconciliationLinesRequest.LineEntry(
                        null, "PROD-UID-001", new BigDecimal("40"), new BigDecimal("10"), null, null)));

        service.enterLines("VR-UID-1", req);

        // expected = 50 - 40 - 0 = 10 ; variance = 10 - 10 = 0
        assertThat(line.getExpectedQty()).isEqualByComparingTo("10");
        assertThat(line.getVarianceQty()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void enterLines_loadedReturnedOverride_recomputesExpected() {
        VanReconciliation recon = openRecon();
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        VanReconciliationLine line = newLine(recon, 1L, PRODUCT_ID,
                new BigDecimal("100"), new BigDecimal("25"));
        when(lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId()))
                .thenReturn(List.of(line));

        // Agent corrects loaded 100 -> 110 (manual load not captured by a transfer)
        EnterVanReconciliationLinesRequest req = new EnterVanReconciliationLinesRequest(List.of(
                new EnterVanReconciliationLinesRequest.LineEntry(
                        1L, null, new BigDecimal("70"), new BigDecimal("15"),
                        new BigDecimal("110"), new BigDecimal("25"))));

        service.enterLines("VR-UID-1", req);

        assertThat(line.getLoadedQty()).isEqualByComparingTo("110");
        // expected = 110 - 70 - 25 = 15 ; variance = 15 - 15 = 0
        assertThat(line.getExpectedQty()).isEqualByComparingTo("15");
        assertThat(line.getVarianceQty()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void enterLines_afterReconciled_rejected() {
        VanReconciliation recon = openRecon();
        recon.reconcile(USER_ID);
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        EnterVanReconciliationLinesRequest req = new EnterVanReconciliationLinesRequest(List.of(
                new EnterVanReconciliationLinesRequest.LineEntry(
                        1L, null, BigDecimal.ZERO, BigDecimal.ZERO, null, null)));

        assertThatThrownBy(() -> service.enterLines("VR-UID-1", req))
                .isInstanceOf(IllegalStateException.class);
    }

    // -------------------------------------------------------------------------
    // enterLines(): sold-only entry (physicalQty null) — backend review fix #1.
    // physicalQty is now OPTIONAL on the request DTO; a sold-only entry must still persist and
    // recompute expected. variance_qty is NOT NULL DEFAULT 0 in the V85 DDL, so it stays ZERO (not
    // null) until physical is entered; variance_value (nullable column) stays null.
    // -------------------------------------------------------------------------

    @Test
    void enterLines_soldOnly_physicalNull_persistsSoldRecomputesExpected_varianceStaysZeroNotValue() {
        VanReconciliation recon = openRecon();
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        VanReconciliationLine line = newLine(recon, 1L, PRODUCT_ID,
                new BigDecimal("100"), new BigDecimal("25")); // loaded=100 returned=25
        when(lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId()))
                .thenReturn(List.of(line));

        // physicalQty omitted (null) — the agent has keyed sold but not yet done the physical count.
        EnterVanReconciliationLinesRequest req = new EnterVanReconciliationLinesRequest(List.of(
                new EnterVanReconciliationLinesRequest.LineEntry(
                        1L, null, new BigDecimal("70"), null, null, null)));

        service.enterLines("VR-UID-1", req);

        assertThat(line.getSoldQty()).isEqualByComparingTo("70");
        assertThat(line.getPhysicalQty()).isNull();
        // expected = 100 - 70 - 25 = 5, computed regardless of physical being entered.
        assertThat(line.getExpectedQty()).isEqualByComparingTo("5");
        // variance_qty column is NOT NULL DEFAULT 0 (V85) — stays ZERO, not the would-be variance.
        assertThat(line.getVarianceQty()).isEqualByComparingTo(BigDecimal.ZERO);
        // variance_value is nullable and stays null — not meaningful without a physical count.
        assertThat(line.getVarianceValue()).isNull();

        verify(lineRepo).save(line);
    }

    // -------------------------------------------------------------------------
    // enterLines(): version-touches the header — backend review fix #2 (freeze-under-concurrency).
    // -------------------------------------------------------------------------

    @Test
    void enterLines_touchesHeader_soItParticipatesInOptimisticLocking() {
        VanReconciliation recon = openRecon();
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        VanReconciliationLine line = newLine(recon, 1L, PRODUCT_ID, BigDecimal.ZERO, BigDecimal.ZERO);
        when(lineRepo.findByVanReconciliationIdOrderByLineNoAsc(recon.getId()))
                .thenReturn(List.of(line));

        EnterVanReconciliationLinesRequest req = new EnterVanReconciliationLinesRequest(List.of(
                new EnterVanReconciliationLinesRequest.LineEntry(
                        1L, null, BigDecimal.ZERO, null, null, null)));

        assertThat(recon.getUpdatedAt()).isNull();

        service.enterLines("VR-UID-1", req);

        // The header itself was saved (dirtied via touch()) so a concurrent reconcile()/cancel()
        // racing on the same @Version row now conflicts instead of silently losing the freeze.
        verify(reconciliations).save(recon);
        assertThat(recon.getUpdatedAt()).isNotNull();
        assertThat(recon.getUpdatedBy()).isEqualTo(USER_ID);
    }

    // -------------------------------------------------------------------------
    // reconcile(): RECORD-ONLY (D-8.2) — status flip + informational outbox, no stock posting.
    // VanReconciliationServiceImpl is not given a StockPostingService/GLPostingService collaborator
    // at all, so there is no way for reconcile() to post a movement or GL journal.
    // -------------------------------------------------------------------------

    @Test
    void reconcile_marksReconciled_andPublishesInformationalOutboxEventOnly() {
        VanReconciliation recon = openRecon();
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        VanReconciliationDto dto = service.reconcile("VR-UID-1");

        assertThat(dto.status()).isEqualTo(VanReconciliationStatus.RECONCILED);
        verify(outbox).publish(
                eq(DomainEventType.VAN_RECONCILED), eq(DomainEventType.AGG_VAN_RECONCILIATION),
                eq(recon.getId()), eq(recon.getUid()), eq(COMPANY_ID), eq(BRANCH_ID), any());
    }

    @Test
    void reconcile_notOpen_rejected() {
        VanReconciliation recon = openRecon();
        recon.cancel(USER_ID);
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        assertThatThrownBy(() -> service.reconcile("VR-UID-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void reconcile_calledTwice_secondCallRejected_noDoublePublish() {
        VanReconciliation recon = openRecon();
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        service.reconcile("VR-UID-1");

        assertThatThrownBy(() -> service.reconcile("VR-UID-1"))
                .isInstanceOf(IllegalStateException.class);

        verify(outbox, org.mockito.Mockito.times(1)).publish(
                eq(DomainEventType.VAN_RECONCILED), any(), any(), any(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // cancel()
    // -------------------------------------------------------------------------

    @Test
    void cancel_open_marksCancelled() {
        VanReconciliation recon = openRecon();
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        VanReconciliationDto dto = service.cancel("VR-UID-1");

        assertThat(dto.status()).isEqualTo(VanReconciliationStatus.CANCELLED);
    }

    @Test
    void cancel_afterReconciled_rejected() {
        VanReconciliation recon = openRecon();
        recon.reconcile(USER_ID);
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        assertThatThrownBy(() -> service.cancel("VR-UID-1"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void cancel_alreadyCancelled_rejected_noDuplicateReStamp() {
        // backend review fix #3: cancel() previously only blocked status == RECONCILED, so
        // re-cancelling an already-CANCELLED worksheet re-stamped cancelled_at/cancelled_by and
        // emitted a duplicate CANCEL audit. Guard now requires OPEN, mirroring reconcile().
        VanReconciliation recon = openRecon();
        when(reconciliations.findByUid("VR-UID-1")).thenReturn(Optional.of(recon));

        service.cancel("VR-UID-1");
        Instant firstCancelledAt = recon.getCancelledAt();

        assertThatThrownBy(() -> service.cancel("VR-UID-1"))
                .isInstanceOf(IllegalStateException.class);

        // cancelled_at must not have been re-stamped by the rejected second call.
        assertThat(recon.getCancelledAt()).isEqualTo(firstCancelledAt);
        verify(audit, org.mockito.Mockito.times(1)).record(any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private StockLocation vanLocation(Long agentId) {
        StockLocation van = mock(StockLocation.class);
        when(van.getId()).thenReturn(VAN_LOCATION_ID);
        when(van.getBranchId()).thenReturn(BRANCH_ID);
        when(van.getCompanyId()).thenReturn(COMPANY_ID);
        when(van.getLocationType()).thenReturn(LocationType.VAN);
        when(van.getAgentId()).thenReturn(agentId);
        when(van.getUid()).thenReturn("VAN-UID-001");
        when(van.getName()).thenReturn("Van 1");
        return van;
    }

    private VanReconciliation openRecon() {
        VanReconciliation recon = new VanReconciliation(COMPANY_ID, BRANCH_ID, "VR-0001",
                VAN_LOCATION_ID, null, LocalDate.of(2026, 7, 4), null, USER_ID);
        setId(recon, 100L);
        setUid(recon, "VR-UID-1");
        return recon;
    }

    private VanReconciliationLine newLine(VanReconciliation recon, Long id, Long productId,
                                          BigDecimal loaded, BigDecimal returned) {
        VanReconciliationLine line = new VanReconciliationLine(
                recon.getId(), (short) 1, productId, "P001", "Widget", USER_ID);
        line.applyDerived(loaded, returned, null);
        setLineId(line, id);
        return line;
    }

    private StockOnHand onHand(BigDecimal avgCost) {
        StockOnHand soh = mock(StockOnHand.class);
        when(soh.getAvgCost()).thenReturn(avgCost);
        return soh;
    }

    private ProductDto productDto() {
        return new ProductDto(PRODUCT_ID, "PROD-UID-001", COMPANY_ID,
                "P001", "Widget A", null, null,
                true, true, false, false, false, null, null, null,
                null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, false, null, null,
                false, null, null, null);   // D-1b weighed/tare/scaleStep + maxSaleWeight
    }

    private static void setId(Object entity, Long id) {
        try {
            var f = com.erp.platform.common.domain.UidEntity.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(entity, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setUid(Object entity, String uid) {
        try {
            var m = com.erp.platform.common.domain.UidEntity.class
                    .getDeclaredMethod("setUid", String.class);
            m.setAccessible(true);
            m.invoke(entity, uid);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }

    private static void setLineId(VanReconciliationLine line, Long id) {
        try {
            var f = VanReconciliationLine.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(line, id);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
