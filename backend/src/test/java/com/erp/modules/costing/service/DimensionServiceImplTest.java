package com.erp.modules.costing.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.erp.modules.costing.domain.dto.CreateDimensionValueRequest;
import com.erp.modules.costing.repository.DimensionRepository;
import com.erp.modules.costing.repository.DimensionValueRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

/**
 * Unit tests for DimensionServiceImpl — focused on defect #3:
 * null/blank dimensionUid must produce a 400-mapped IllegalArgumentException,
 * not a NotFoundException("Dimension not found: null") which produced a 404.
 */
class DimensionServiceImplTest {

    private DimensionRepository      dimensions;
    private DimensionValueRepository values;
    private ScopeGuard               scopeGuard;
    private AuditService             audit;
    private DimensionServiceImpl     service;

    @BeforeEach
    void setUp() {
        dimensions = mock(DimensionRepository.class);
        values     = mock(DimensionValueRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        audit      = mock(AuditService.class);
        service    = new DimensionServiceImpl(dimensions, values, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(1L, "tester@test.com", false, 10L, null, null));
    }

    @AfterEach
    void clearContext() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Defect #3: createValue with null/blank dimensionUid → IllegalArgumentException (maps to 400)
    // -------------------------------------------------------------------------

    @Test
    void createValue_nullDimensionUid_throwsIllegalArgument() {
        CreateDimensionValueRequest req = new CreateDimensionValueRequest(null, "CC-01", "Cost Centre 1", null);

        assertThatThrownBy(() -> service.createValue(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensionUid is required");

        // Repo must never be touched — the guard fires before the lookup
        verify(dimensions, never()).findByUid(null);
    }

    @Test
    void createValue_blankDimensionUid_throwsIllegalArgument() {
        CreateDimensionValueRequest req = new CreateDimensionValueRequest("   ", "CC-01", "Cost Centre 1", null);

        assertThatThrownBy(() -> service.createValue(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensionUid is required");

        verify(dimensions, never()).findByUid("   ");
    }

    // -------------------------------------------------------------------------
    // Defect #3: listValues with null/blank dimensionUid → IllegalArgumentException (maps to 400)
    // -------------------------------------------------------------------------

    @Test
    void listValues_nullDimensionUid_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.listValues(null, Pageable.unpaged()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensionUid is required");

        verify(dimensions, never()).findByUid(null);
    }

    @Test
    void listValues_blankDimensionUid_throwsIllegalArgument() {
        assertThatThrownBy(() -> service.listValues("", Pageable.unpaged()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("dimensionUid is required");

        verify(dimensions, never()).findByUid("");
    }
}
