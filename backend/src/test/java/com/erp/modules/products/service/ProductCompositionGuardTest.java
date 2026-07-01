package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.domain.MasterStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ProductCompositionGuard}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Defect 6a (Low): self-reference (a product added as its own component) must produce
 *       a clear {@link IllegalArgumentException} BEFORE the DB CHECK
 *       ({@code chk_product_component_not_self}) fires a generic constraint error (BR-PROD-05).
 *   <li>Existing rules: cross-company component → {@link ForbiddenException};
 *       ARCHIVED component → {@link IllegalArgumentException}.
 * </ul>
 *
 * <p>Note: Defect 6b (SERVICE + stockable) is guarded in {@link ProductServiceImpl};
 * see {@link ProductServiceImplTest}.
 */
class ProductCompositionGuardTest {

    private static final Long COMPANY_A = 10L;
    private static final Long COMPANY_B = 20L;

    private ProductCompositionGuard guard;

    @BeforeEach
    void setUp() {
        guard = new ProductCompositionGuard();
    }

    // -------------------------------------------------------------------------
    // Defect 6a: self-reference → clear IllegalArgumentException before DB fires
    // -------------------------------------------------------------------------

    @Test
    void assertCanAddComponent_selfReference_throwsIllegalArgument() {
        Product p = product(1L, COMPANY_A, MasterStatus.ACTIVE);

        assertThatThrownBy(() -> guard.assertCanAddComponent(p, p))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be a component of itself");
    }

    @Test
    void assertCanAddComponent_selfReference_sameIdDifferentInstance_throwsIllegalArgument() {
        Product composed  = product(42L, COMPANY_A, MasterStatus.ACTIVE);
        Product component = product(42L, COMPANY_A, MasterStatus.ACTIVE);  // same id, different object

        assertThatThrownBy(() -> guard.assertCanAddComponent(composed, component))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be a component of itself");
    }

    // -------------------------------------------------------------------------
    // Cross-company component → ForbiddenException (BR-PROD-06)
    // -------------------------------------------------------------------------

    @Test
    void assertCanAddComponent_crossCompany_throwsForbiddenException() {
        Product composed  = product(1L, COMPANY_A, MasterStatus.ACTIVE);
        Product component = product(2L, COMPANY_B, MasterStatus.ACTIVE);

        assertThatThrownBy(() -> guard.assertCanAddComponent(composed, component))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("same company");
    }

    // -------------------------------------------------------------------------
    // ARCHIVED component → IllegalArgumentException (BR-PROD-05)
    // -------------------------------------------------------------------------

    @Test
    void assertCanAddComponent_archivedComponent_throwsIllegalArgument() {
        Product composed  = product(1L, COMPANY_A, MasterStatus.ACTIVE);
        Product component = product(2L, COMPANY_A, MasterStatus.ARCHIVED);

        assertThatThrownBy(() -> guard.assertCanAddComponent(composed, component))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("archived");
    }

    // -------------------------------------------------------------------------
    // Valid: different products, same company, both ACTIVE → no exception
    // -------------------------------------------------------------------------

    @Test
    void assertCanAddComponent_validPair_noException() {
        Product composed  = product(1L, COMPANY_A, MasterStatus.ACTIVE);
        Product component = product(2L, COMPANY_A, MasterStatus.ACTIVE);

        assertThatCode(() -> guard.assertCanAddComponent(composed, component))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Edge: composed id is null (unsaved entity) — no self-ref false positive
    // -------------------------------------------------------------------------

    @Test
    void assertCanAddComponent_composedIdNull_sameCompany_noException() {
        Product composed  = product(null, COMPANY_A, MasterStatus.ACTIVE);
        Product component = product(2L,   COMPANY_A, MasterStatus.ACTIVE);

        // null id on composed means it's not yet persisted — cannot self-reference
        assertThatCode(() -> guard.assertCanAddComponent(composed, component))
                .doesNotThrowAnyException();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private Product product(Long id, Long companyId, MasterStatus status) {
        Product p = mock(Product.class);
        when(p.getId()).thenReturn(id);
        when(p.getCompanyId()).thenReturn(companyId);
        when(p.getStatus()).thenReturn(status);
        return p;
    }
}
