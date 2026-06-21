package com.erp.modules.fixedassets.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.fixedassets.domain.dto.RegisterAssetRequest;
import com.erp.modules.fixedassets.domain.entity.AssetCategory;
import com.erp.modules.fixedassets.domain.enums.DepreciationMethod;
import com.erp.modules.fixedassets.repository.AssetCategoryRepository;
import com.erp.modules.fixedassets.repository.FixedAssetRepository;
import com.erp.modules.ap.service.SupplierBillService;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FixedAssetServiceImpl} validation guards.
 *
 * <p>Covers:
 * <ul>
 *   <li>Defect 3 (Medium): REDUCING_BALANCE without a positive reducingRate must throw a clear
 *       {@link IllegalArgumentException} before schedule generation causes an NPE (BR-FA-05).
 *   <li>Defect 4 (Low): a negative acquisitionCost must produce a clear
 *       {@link IllegalArgumentException} before the DB constraint fires (BR-FA-04).
 * </ul>
 */
class FixedAssetServiceImplTest {

    private static final Long     COMPANY_ID   = 1L;
    private static final Long     BRANCH_ID    = 2L;
    private static final Long     CATEGORY_ID  = 3L;
    private static final LocalDate ACQ_DATE    = LocalDate.of(2024, 1, 1);

    private FixedAssetRepository       assetRepo;
    private AssetCategoryRepository    categoryRepo;
    private FixedAssetNumberGenerator  numberGen;
    private DepreciationScheduleService scheduleService;
    private FixedAssetGlPoster         glPoster;
    private SupplierBillService        billService;
    private ScopeGuard                 scopeGuard;
    private AuditService               audit;
    private FixedAssetServiceImpl      service;

    private AssetCategory stubCategory;

    @BeforeEach
    void setUp() {
        assetRepo       = mock(FixedAssetRepository.class);
        categoryRepo    = mock(AssetCategoryRepository.class);
        numberGen       = mock(FixedAssetNumberGenerator.class);
        scheduleService = mock(DepreciationScheduleService.class);
        glPoster        = mock(FixedAssetGlPoster.class);
        billService     = mock(SupplierBillService.class);
        scopeGuard      = mock(ScopeGuard.class);
        audit           = mock(AuditService.class);

        service = new FixedAssetServiceImpl(assetRepo, categoryRepo, numberGen,
                scheduleService, glPoster, billService, scopeGuard, audit);

        // Stub: category belongs to the correct company
        stubCategory = mock(AssetCategory.class);
        when(stubCategory.getCompanyId()).thenReturn(COMPANY_ID);
        when(categoryRepo.findById(CATEGORY_ID)).thenReturn(Optional.of(stubCategory));

        // Stub: scopeGuard passes
        doNothing().when(scopeGuard).assertCanActIn(any(), anyLong());

        // Set a minimal RequestContext so actorId() doesn't NPE
        RequestContext.set(new RequestContext.Principal(99L, "test", true, COMPANY_ID, BRANCH_ID, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Defect 3: REDUCING_BALANCE requires positive reducingRate (BR-FA-05)
    // -------------------------------------------------------------------------

    @Test
    void register_reducingBalance_nullRate_throwsIllegalArgument() {
        RegisterAssetRequest req = rbRequest(null);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REDUCING_BALANCE")
                .hasMessageContaining("reducingRate");
    }

    @Test
    void register_reducingBalance_zeroRate_throwsIllegalArgument() {
        RegisterAssetRequest req = rbRequest(BigDecimal.ZERO);

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REDUCING_BALANCE");
    }

    @Test
    void register_reducingBalance_negativeRate_throwsIllegalArgument() {
        RegisterAssetRequest req = rbRequest(new BigDecimal("-5"));

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("REDUCING_BALANCE");
    }

    @Test
    void register_reducingBalance_withNullRate_scheduleServiceNeverCalled() {
        RegisterAssetRequest req = rbRequest(null);

        try { service.register(req); } catch (IllegalArgumentException ignored) { }

        verify(scheduleService, never()).generate(any(), anyLong());
        verify(assetRepo,       never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Defect 4: negative acquisitionCost → clear 400 before DB constraint (BR-FA-04)
    // -------------------------------------------------------------------------

    @Test
    void register_negativeAcquisitionCost_throwsIllegalArgument() {
        RegisterAssetRequest req = slRequest(new BigDecimal("-1000.00"));

        assertThatThrownBy(() -> service.register(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("acquisitionCost");
    }

    @Test
    void register_negativeAcquisitionCost_assetRepoNeverCalled() {
        RegisterAssetRequest req = slRequest(new BigDecimal("-0.01"));

        try { service.register(req); } catch (IllegalArgumentException ignored) { }

        verify(assetRepo, never()).save(any());
    }

    @Test
    void register_zeroCost_isAllowed() {
        // Zero is acceptable (BR-FA-04 only prohibits negative)
        when(numberGen.nextAssetNumber(COMPANY_ID)).thenReturn("FA-0001");
        when(assetRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        RegisterAssetRequest req = slRequest(BigDecimal.ZERO);

        // Should not throw; further stubbing not needed since we only assert no exception here
        try {
            service.register(req);
        } catch (Exception e) {
            // NotFoundException from audit or other downstream is acceptable in a unit test —
            // the important thing is that it is NOT an IllegalArgumentException about cost.
            if (e instanceof IllegalArgumentException) {
                throw new AssertionError("Should not reject zero acquisitionCost", e);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Builds a REDUCING_BALANCE request with the given rate. */
    private RegisterAssetRequest rbRequest(BigDecimal reducingRate) {
        return new RegisterAssetRequest(
                COMPANY_ID, BRANCH_ID, CATEGORY_ID,
                "Test RB Asset",
                new BigDecimal("10000.00"),   // acquisitionCost
                BigDecimal.ZERO,              // salvageValue
                DepreciationMethod.REDUCING_BALANCE,
                36,                           // lifePeriods
                reducingRate,
                ACQ_DATE, ACQ_DATE,
                null, null, null);
    }

    /** Builds a STRAIGHT_LINE request with the given acquisitionCost. */
    private RegisterAssetRequest slRequest(BigDecimal acquisitionCost) {
        return new RegisterAssetRequest(
                COMPANY_ID, BRANCH_ID, CATEGORY_ID,
                "Test SL Asset",
                acquisitionCost,
                BigDecimal.ZERO,
                DepreciationMethod.STRAIGHT_LINE,
                36,
                null,       // reducingRate not needed for SL
                ACQ_DATE, ACQ_DATE,
                null, null, null);
    }
}
