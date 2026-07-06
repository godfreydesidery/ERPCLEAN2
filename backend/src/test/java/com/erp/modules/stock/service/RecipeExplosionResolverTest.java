package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.products.domain.dto.ProductComponentDto;
import com.erp.modules.products.service.BomExplosionService;
import com.erp.modules.products.service.ProductService;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * ADR-0058: what explodes into components at issue time (sale / delivery) vs what is issued as
 * itself. The reported defect was a stockable, make-to-stock finished good with an ACTIVE
 * manufacturing BOM being re-exploded at sale (double-consuming components, never relieving the
 * finished good). {@link RecipeExplosionResolver#shouldExplodeAtIssue} is the single decision point.
 */
class RecipeExplosionResolverTest {

    private static final String UID = "PROD-UID-1";

    private ProductService products;
    private BomExplosionService boms;
    private RecipeExplosionResolver resolver;

    @BeforeEach
    void setUp() {
        products = mock(ProductService.class);
        boms = mock(BomExplosionService.class);
        resolver = new RecipeExplosionResolver(products, boms);
    }

    private void withKitRecipe(boolean present) {
        when(products.listComponents(UID)).thenReturn(present
                ? List.of(new ProductComponentDto(1L, 1L, 2L, "C-UID", "C", "Comp", BigDecimal.ONE))
                : List.of());
    }

    @Test
    void stockableFinishedGoodWithOnlyManufacturingBom_isNotExploded() {
        withKitRecipe(false);
        when(boms.hasActiveBom(UID)).thenReturn(true);
        // ADR-0058: make-to-stock finished good is issued as itself, NOT exploded.
        assertThat(resolver.shouldExplodeAtIssue(UID, true)).isFalse();
    }

    @Test
    void nonStockableBomPhantom_isExploded() {
        withKitRecipe(false);
        when(boms.hasActiveBom(UID)).thenReturn(true);
        // ADR-0026 D-7: a non-stockable phantom assembled via a BOM still explodes.
        assertThat(resolver.shouldExplodeAtIssue(UID, false)).isTrue();
    }

    @Test
    void stockableKitWithProductComponents_isExploded() {
        withKitRecipe(true);
        // ADR-0010 D-8: a point-of-sale kit recipe always explodes (stockable or not).
        assertThat(resolver.shouldExplodeAtIssue(UID, true)).isTrue();
    }

    @Test
    void plainStockableProductWithNoRecipe_isNotExploded() {
        withKitRecipe(false);
        when(boms.hasActiveBom(UID)).thenReturn(false);
        assertThat(resolver.shouldExplodeAtIssue(UID, true)).isFalse();
    }

    @Test
    void nonStockableProductWithNoRecipe_isNotExploded() {
        withKitRecipe(false);
        when(boms.hasActiveBom(UID)).thenReturn(false);
        assertThat(resolver.shouldExplodeAtIssue(UID, false)).isFalse();
    }
}
