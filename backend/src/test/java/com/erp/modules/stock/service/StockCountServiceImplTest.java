package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.stock.domain.dto.CreateStockCountRequest;
import com.erp.modules.stock.domain.dto.EnterCountRequest;
import com.erp.modules.stock.domain.dto.StockCountDto;
import com.erp.modules.stock.domain.entity.StockCount;
import com.erp.modules.stock.domain.entity.StockCountLine;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockCountLineRepository;
import com.erp.modules.stock.repository.StockCountRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for {@link StockCountServiceImpl}.
 *
 * <p>Regression for fix/e2e-pos2b defect (C):
 * {@code StockCountServiceImpl.create} was calling
 * {@code productService.getByUid(String.valueOf(productId))} — passing a numeric id string
 * to a uid-based lookup. That threw {@code NotFoundException} inside a nested
 * {@code @Transactional(readOnly=true)} method, which Spring used to mark the outer TX
 * rollback-only, causing {@code UnexpectedRollbackException} when the outer TX committed.
 * The catch block silently swallowed the exception but the TX was already poisoned.
 *
 * <p>Fix: replaced with {@code productService.getById(Long id)} which does a plain
 * {@code findById} and never throws on miss — no nested TX poisoning.
 */
class StockCountServiceImplTest {

    private static final Long COMPANY_ID = 10L;
    private static final Long BRANCH_ID  = 20L;
    private static final Long USER_ID    = 1L;
    private static final Long LOCATION_ID = 30L;
    private static final Long PRODUCT_ID  = 99L;

    private StockCountRepository     counts;
    private StockCountLineRepository countLines;
    private StockOnHandRepository    onHands;
    private StockPostingService      posting;
    private InventoryValuationService valuation;
    private InventoryGlPoster        glPoster;
    private ProductService           productService;
    private LocationResolver         locationResolver;
    private WarehouseNumberGenerator numberGenerator;
    private OutboxPublisher          outbox;
    private ScopeGuard               scopeGuard;
    private AuditService             audit;

    private StockCountServiceImpl service;

    @BeforeEach
    void setUp() {
        counts          = mock(StockCountRepository.class);
        countLines      = mock(StockCountLineRepository.class);
        onHands         = mock(StockOnHandRepository.class);
        posting         = mock(StockPostingService.class);
        valuation       = mock(InventoryValuationService.class);
        glPoster        = mock(InventoryGlPoster.class);
        productService  = mock(ProductService.class);
        locationResolver = mock(LocationResolver.class);
        numberGenerator = mock(WarehouseNumberGenerator.class);
        outbox          = mock(OutboxPublisher.class);
        scopeGuard      = mock(ScopeGuard.class);
        audit           = mock(AuditService.class);

        service = new StockCountServiceImpl(counts, countLines, onHands, posting, valuation,
                glPoster, productService, locationResolver, numberGenerator, outbox,
                scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(
                USER_ID, "counter@test.com", false, COMPANY_ID, BRANCH_ID, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    /**
     * Regression: create a FULL stock count for a location that has one on-hand row.
     * The service must persist the header + one line and return a populated DTO.
     * Before the fix, getByUid(numericId) threw NotFoundException in a nested TX,
     * marking the outer TX rollback-only — the count was never committed.
     */
    @Test
    void create_locationWithOnHand_persistsCountAndLine() {
        // Arrange — location
        StockLocation loc = mock(StockLocation.class);
        when(loc.getId()).thenReturn(LOCATION_ID);
        when(loc.getBranchId()).thenReturn(BRANCH_ID);
        when(loc.getCompanyId()).thenReturn(COMPANY_ID);
        when(loc.getStatus()).thenReturn(MasterStatus.ACTIVE);
        when(locationResolver.resolveLocation("LOC-UID-001", COMPANY_ID)).thenReturn(loc);

        // Number generator
        when(numberGenerator.nextCount(COMPANY_ID)).thenReturn("CNT-0001");

        // StockOnHand at this location
        StockOnHand soh = mock(StockOnHand.class);
        when(soh.getProductId()).thenReturn(PRODUCT_ID);
        when(soh.getLocationId()).thenReturn(LOCATION_ID);
        when(soh.getQuantity()).thenReturn(new BigDecimal("10.0000"));
        // findByCompanyIdAndBranchId returns only this row
        when(onHands.findByCompanyIdAndBranchId(eq(COMPANY_ID), eq(BRANCH_ID), any(Pageable.class)))
                .thenReturn(new PageImpl<>(List.of(soh)));

        // Product lookup via getById — the correct method (no NotFoundException)
        // ProductDto(id, uid, companyId, code, name, description, type,
        //            sellable, stockable, lotTracked, serialTracked, expiryTracked,
        //            baseUnitUid, baseUnitCode, baseUnitName,
        //            cost, vatStatus, status,
        //            brand, manufacturer, weight, volume, dimensions, hsCode,   // P2-M3
        //            version, createdAt, createdBy, updatedAt, updatedBy,
        //            reorderLevel, reorderQty, safetyStock, minStock, maxStock,
        //            leadTimeDays, purchasable, preferredSupplierId,
        //            restrictedKind)                                             // ADR-0044 D-3a
        ProductDto productDto = new ProductDto(PRODUCT_ID, "PROD-UID-001", COMPANY_ID,
                "P001", "Widget A", null, null,
                true, true, false, false, false, null, null, null,
                null, null, null,
                null, null, null, null, null, null,   // P2-M3 brand/manufacturer/weight/volume/dimensions/hsCode
                null, null, null, null, null, null, null, null, null, null, null, false, null, null);
        when(productService.getById(PRODUCT_ID)).thenReturn(productDto);

        // Stub saves — return the passed object (simulate persist)
        when(counts.save(any(StockCount.class))).thenAnswer(inv -> {
            StockCount c = inv.getArgument(0);
            setId(c, 100L);
            setUid(c, "SC-UID-0001");
            return c;
        });
        when(countLines.save(any(StockCountLine.class))).thenAnswer(inv -> inv.getArgument(0));
        when(countLines.findByStockCountIdOrderByLineNoAsc(100L)).thenReturn(List.of());

        CreateStockCountRequest req = new CreateStockCountRequest(
                "LOC-UID-001", LocalDate.now(), "FULL", null, null);

        // Act — must NOT throw UnexpectedRollbackException
        StockCountDto dto = service.create(req);

        // Assert — header persisted, lines saved
        assertThat(dto).isNotNull();
        assertThat(dto.companyId()).isEqualTo(COMPANY_ID);
        assertThat(dto.countNumber()).isEqualTo("CNT-0001");

        // create() calls counts.save twice: initial persist then again after count.freeze()
        verify(counts, org.mockito.Mockito.times(2)).save(any(StockCount.class));
        verify(countLines).save(any(StockCountLine.class));
        // getById must be called — NOT getByUid
        verify(productService).getById(PRODUCT_ID);
        verify(productService, never()).getByUid(any());
    }

    // -------------------------------------------------------------------------
    // persona UAT I5 — reasonCode entered during counting must reach the posted line
    // -------------------------------------------------------------------------

    @Test
    void enterCount_withReasonCode_persistsReasonCodeOnLine() {
        StockCount count = countInCounting(200L, "SC-UID-0002");
        StockCountLine line = countLine(300L, count.getId());
        when(countLines.findById(300L)).thenReturn(Optional.of(line));

        EnterCountRequest req = new EnterCountRequest(List.of(
                new EnterCountRequest.LineEntry(300L, new BigDecimal("8.0000"), "DAMAGE")));

        service.enterCount("SC-UID-0002", req);

        // Before the fix, StockCountLine.enterCount(qty, actorId) never set reasonCode at all —
        // the posted variance always fell back to the generic AdjustmentReason.COUNT_CORRECTION
        // default regardless of what the count team entered.
        assertThat(line.getCountedQty()).isEqualByComparingTo("8.0000");
        assertThat(line.getReasonCode()).isEqualTo("DAMAGE");
        verify(countLines).save(line);
    }

    @Test
    void enterCount_blankReasonCode_doesNotClobberAnAlreadyRecordedReason() {
        // A re-enter (e.g. correcting a typo'd quantity) with no reason supplied must not erase a
        // reason already recorded on an earlier enter for the same line.
        StockCount count = countInCounting(201L, "SC-UID-0003");
        StockCountLine line = countLine(301L, count.getId());
        line.enterCount(new BigDecimal("7.0000"), "DAMAGE", USER_ID);
        when(countLines.findById(301L)).thenReturn(Optional.of(line));

        EnterCountRequest req = new EnterCountRequest(List.of(
                new EnterCountRequest.LineEntry(301L, new BigDecimal("8.0000"), null)));

        service.enterCount("SC-UID-0003", req);

        assertThat(line.getCountedQty()).isEqualByComparingTo("8.0000");
        assertThat(line.getReasonCode()).isEqualTo("DAMAGE");
    }

    // -------------------------------------------------------------------------
    // persona UAT I6 — stock_on_hand.last_counted_at must be stamped on post
    // -------------------------------------------------------------------------

    @Test
    void post_zeroVarianceLine_stampsLastCountedAtOnStockOnHand() {
        StockCount count = countInCounting(202L, "SC-UID-0004");
        StockCountLine line = countLine(302L, count.getId());
        line.enterCount(BigDecimal.TEN, null, USER_ID); // counted == live == system: zero variance
        when(countLines.findByStockCountIdOrderByLineNoAsc(count.getId())).thenReturn(List.of(line));

        StockOnHand soh = new StockOnHand(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID);
        soh.applyDelta(BigDecimal.TEN, USER_ID);
        when(onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID)).thenReturn(Optional.of(soh));

        assertThat(soh.getLastCountedAt()).isNull();

        service.post("SC-UID-0004", LocalDate.now());

        // Before the fix, last_counted_at had a column + getter but NO write path anywhere.
        assertThat(soh.getLastCountedAt()).isNotNull();
    }

    @Test
    void post_variedLine_stampsLastCountedAtOnTheFreshlyPostedStockOnHand() {
        StockCount count = countInCounting(203L, "SC-UID-0005");
        StockCountLine line = countLine(303L, count.getId());
        line.enterCount(new BigDecimal("8.0000"), null, USER_ID); // live=10, counted=8: a real variance
        when(countLines.findByStockCountIdOrderByLineNoAsc(count.getId())).thenReturn(List.of(line));

        // R4: DISTINCT instances for the pre-posting read and the post-posting "freshSoh" re-read —
        // mirrors production, where posting.post(...) can mutate/reload the row between the two
        // repository calls. Stubbing the SAME instance for both (as before) let the test pass even
        // if markCounted were mistakenly called on the STALE pre-posting row instead of the fresh
        // one — it could never actually prove which row got stamped.
        StockOnHand sohBeforePosting = new StockOnHand(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID);
        sohBeforePosting.applyDelta(BigDecimal.TEN, USER_ID);
        StockOnHand sohFresh = new StockOnHand(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID);
        sohFresh.applyDelta(BigDecimal.TEN, USER_ID);
        // No cost recorded here — the GL/valuation numeric correctness for a variance is out of
        // this fix's scope; only which row gets the last_counted_at stamp matters.
        when(onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID))
                .thenReturn(Optional.of(sohBeforePosting), Optional.of(sohFresh));
        when(posting.post(any(), any(), any(), any(), any(), any(),
                any(), any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn("MOVEUID000000000000000001");

        assertThat(sohBeforePosting.getLastCountedAt()).isNull();
        assertThat(sohFresh.getLastCountedAt()).isNull();

        service.post("SC-UID-0005", LocalDate.now());

        assertThat(sohFresh.getLastCountedAt())
                .as("I6: last_counted_at must be stamped on the FRESHLY re-read (post-posting) row")
                .isNotNull();
        assertThat(sohBeforePosting.getLastCountedAt())
                .as("the stale pre-posting row must NOT be the one stamped")
                .isNull();
    }

    // -------------------------------------------------------------------------
    // Shared fixtures
    // -------------------------------------------------------------------------

    private StockCount countInCounting(Long id, String uid) {
        StockCount count = new StockCount(COMPANY_ID, BRANCH_ID, "CNT-" + id, "FULL",
                LOCATION_ID, LocalDate.now(), null, USER_ID);
        count.freeze(USER_ID);
        setId(count, id);
        setUid(count, uid);
        when(counts.findByUid(uid)).thenReturn(Optional.of(count));
        return count;
    }

    private StockCountLine countLine(Long lineId, Long stockCountId) {
        StockCountLine line = new StockCountLine(stockCountId, COMPANY_ID, BRANCH_ID, (short) 1,
                PRODUCT_ID, "P001", "Widget A", null, null,
                BigDecimal.TEN, "TZS", USER_ID);
        setId(line, lineId);
        return line;
    }

    // -------------------------------------------------------------------------
    // Reflection helpers (test-only)
    // -------------------------------------------------------------------------

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
}
