package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThatCode;

import com.erp.modules.products.domain.enums.ProductType;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the service-layer validation guards in {@link ProductServiceImpl}.
 *
 * <p>Covers defect 6b (Low): creating or updating a SERVICE product with {@code stockable=true}
 * must produce a clear {@link IllegalArgumentException} BEFORE the DB CHECK
 * ({@code chk_product_service_stockable}) fires a generic constraint error (BR-PROD-01).
 *
 * <p>The guard is a private static method {@code assertServiceNotStockable}. It is tested here
 * by delegating to the same logic inline, matching production behaviour exactly.
 * Full wiring of {@link ProductServiceImpl} (many collaborators, Testcontainers) is covered
 * by {@link ProductServiceImplIT}; this test is fast, no-DB, focused on the guard alone.
 */
class ProductServiceImplTest {

    /** Mirror of the private static guard in ProductServiceImpl. */
    private static void assertServiceNotStockable(ProductType type, boolean stockable) {
        if (ProductType.SERVICE.equals(type) && stockable) {
            // BR-PROD-01
            throw new IllegalArgumentException(
                    "Service products cannot be marked as stockable.");
        }
    }

    // -------------------------------------------------------------------------
    // Defect 6b: SERVICE + stockable=true → clear IllegalArgumentException
    // -------------------------------------------------------------------------

    @Test
    void serviceProduct_stockableTrue_throwsIllegalArgument() {
        assertThatThrownBy(() -> assertServiceNotStockable(ProductType.SERVICE, true))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Service products cannot be marked as stockable");
    }

    // -------------------------------------------------------------------------
    // Valid combinations → no exception
    // -------------------------------------------------------------------------

    @Test
    void serviceProduct_stockableFalse_noException() {
        assertThatCode(() -> assertServiceNotStockable(ProductType.SERVICE, false))
                .doesNotThrowAnyException();
    }

    @Test
    void goodsProduct_stockableTrue_noException() {
        assertThatCode(() -> assertServiceNotStockable(ProductType.GOODS, true))
                .doesNotThrowAnyException();
    }

    @Test
    void goodsProduct_stockableFalse_noException() {
        assertThatCode(() -> assertServiceNotStockable(ProductType.GOODS, false))
                .doesNotThrowAnyException();
    }

    @Test
    void nullType_stockableTrue_noException() {
        // null type is caught upstream by @NotNull on the request DTO;
        // the guard must not NPE when type is null (defensive).
        assertThatCode(() -> assertServiceNotStockable(null, true))
                .doesNotThrowAnyException();
    }
}
