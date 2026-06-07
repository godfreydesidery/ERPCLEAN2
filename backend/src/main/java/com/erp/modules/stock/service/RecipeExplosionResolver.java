package com.erp.modules.stock.service;

import com.erp.modules.products.domain.dto.ProductComponentDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Single-level recipe explosion for the SALE_ISSUE handler (ADR-0010 D-8, FR-STOCK-08).
 *
 * <p>When a {@code SALE.FINALISED} line names a <em>composed</em> product (one with
 * {@code product_components} rows), this resolver expands it to its stockable components.
 * The composed product's on-hand is never deducted; each stockable component's on-hand is.
 *
 * <p>Non-stockable components are skipped and recorded (D-3, BR-STOCK-04): an INFO log line is
 * written; no movement row is posted (a zero-effect movement is forbidden by
 * {@code chk_stock_movement_qty <> 0}).
 *
 * <p>Single-level only (FR-PROD-16). No recursion. Components that are themselves composed are
 * treated as simple products and deducted directly (multi-level BOM is deferred — stock.md §12).
 *
 * <p>N+1 note (D-8): each component requires a {@code ProductService.getByUid} call to learn
 * {@code stockable}. Fine for v1 (async, small recipes). The clean additive fix is enriching
 * {@code ProductComponentDto} with {@code componentStockable} — flagged in ADR-0010 D-8 / §3.
 */
@Component
public class RecipeExplosionResolver {

    private static final Logger log = LoggerFactory.getLogger(RecipeExplosionResolver.class);

    private final ProductService productService;

    public RecipeExplosionResolver(ProductService productService) {
        this.productService = productService;
    }

    /**
     * Result of an explosion — what the handler should post.
     *
     * @param productId  the stockable component's internal product id
     * @param quantity   the signed delta to post (negative for SALE_ISSUE)
     */
    public record ExplosionLine(Long productId, BigDecimal quantity) {}

    /**
     * Expand a composed line to its stockable components.
     *
     * @param composedProductUid the uid of the composed product (the line product)
     * @param lineQtyInBase      the quantity sold in the composed product's base units
     * @return the list of (component productId, signed delta) pairs to post as SALE_ISSUE movements;
     *         empty list means the composed product had no stockable components (all skipped).
     */
    public List<ExplosionLine> explode(String composedProductUid, BigDecimal lineQtyInBase) {
        List<ProductComponentDto> components = productService.listComponents(composedProductUid);
        List<ExplosionLine> result = new ArrayList<>();

        for (ProductComponentDto comp : components) {
            ProductDto compProduct = productService.getByUid(comp.componentProductUid());
            if (!compProduct.stockable()) {
                log.info("Recipe explosion: skipping non-stockable component uid={} name='{}' " +
                                "of composed product uid={} (BR-STOCK-04, D-3)",
                        comp.componentProductUid(), comp.componentProductName(), composedProductUid);
                // No movement row — zero-effect movement is forbidden by chk_stock_movement_qty
                continue;
            }
            // qty = lineQtyInBase × recipe component quantity (in component base units)
            BigDecimal deduction = lineQtyInBase.multiply(comp.quantity()).negate();
            result.add(new ExplosionLine(compProduct.id(), deduction));
        }
        return result;
    }

    /**
     * Returns true if the product has any product_components (i.e. it is composed / has a recipe).
     * An empty component list means the product is simple.
     */
    public boolean isComposed(String productUid) {
        return !productService.listComponents(productUid).isEmpty();
    }
}
