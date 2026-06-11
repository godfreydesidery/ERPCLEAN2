package com.erp.modules.stock.service;

import com.erp.modules.products.domain.dto.ProductComponentDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.BomExplosionService;
import com.erp.modules.products.service.ProductService;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Recipe / BOM explosion for the SALE_ISSUE handler (ADR-0010 D-8, FR-STOCK-08).
 *
 * <p><strong>Upgrade (ADR-0026 D-7 / NFR-BOM-04 — one explosion implementation):</strong>
 * When a composed product has an <strong>ACTIVE BOM</strong>, this resolver delegates to
 * {@link BomExplosionService} and maps the flattened leaf summary to the existing
 * {@code ExplosionLine} shape — the COGS consumer ({@code SaleIssueStockHandler},
 * {@code DeliveryIssueStockHandler}) is <strong>unchanged</strong>.
 *
 * <p><strong>Sale-explosion depth policy (OQ-BOM-05 — the load-bearing safety property):</strong>
 * {@code multiLevel = false} is ALWAYS passed to {@code BomExplosionService} from this resolver.
 * A sale deducts only the <strong>immediate</strong> components of the composed product, exactly as
 * before (byte-for-byte COGS preservation). Multi-level sale-explosion is a separate, deliberate
 * ADR-decision that this module does NOT make — the flag exists in the resolver and in
 * {@code BomExplosionService} for when that decision is taken in the future.
 *
 * <p>Coexistence (D-5): a product with an ACTIVE BOM resolves via the BOM (MAKE/BUY leaf summary);
 * a product with only {@code product_components} (no BOM) still uses the original single-level path.
 *
 * <p>Non-stockable components are skipped (D-3, BR-STOCK-04): an INFO log line is written;
 * no movement row is posted ({@code chk_stock_movement_qty <> 0}).
 */
@Component
public class RecipeExplosionResolver {

    private static final Logger log = LoggerFactory.getLogger(RecipeExplosionResolver.class);

    /**
     * Sale-explosion depth: single-level (OQ-BOM-05 — COGS byte-for-byte unchanged).
     * Set to {@code true} only after a deliberate future ADR that analyses the full COGS impact.
     */
    private static final boolean SALE_MULTI_LEVEL = false;

    private final ProductService productService;
    private final BomExplosionService bomExplosionService;

    public RecipeExplosionResolver(ProductService productService,
                                   BomExplosionService bomExplosionService) {
        this.productService = productService;
        this.bomExplosionService = bomExplosionService;
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
     * <p>If the product has an ACTIVE BOM, delegates to {@link BomExplosionService} with
     * {@code multiLevel=false} (OQ-BOM-05). Otherwise falls back to the original single-level
     * {@code product_components} path (D-5 coexistence).
     *
     * @param composedProductUid the uid of the composed product (the line product)
     * @param lineQtyInBase      the quantity sold in the composed product's base units
     * @return the list of (component productId, signed delta) pairs to post as SALE_ISSUE movements
     */
    public List<ExplosionLine> explode(String composedProductUid, BigDecimal lineQtyInBase) {
        // D-7: delegate to BomExplosionService when an ACTIVE BOM exists (NFR-BOM-04)
        if (bomExplosionService.hasActiveBom(composedProductUid)) {
            return explodeViaBom(composedProductUid, lineQtyInBase);
        }
        // D-5: legacy single-level product_components path (byte-for-byte unchanged)
        return explodeLegacy(composedProductUid, lineQtyInBase);
    }

    /**
     * Returns true if the product has any recipe: an ACTIVE BOM OR product_components rows.
     * Extended from v1 to also check for ACTIVE BOM (D-7).
     */
    public boolean isComposed(String productUid) {
        return bomExplosionService.hasActiveBom(productUid)
                || !productService.listComponents(productUid).isEmpty();
    }

    // -------------------------------------------------------------------------
    // BOM-aware path (D-7)
    // -------------------------------------------------------------------------

    private List<ExplosionLine> explodeViaBom(String composedProductUid, BigDecimal lineQtyInBase) {
        List<com.erp.modules.products.domain.dto.BomExplosionLeafDto> leaves =
                bomExplosionService.explodeToLeaves(composedProductUid, lineQtyInBase, SALE_MULTI_LEVEL);

        List<ExplosionLine> result = new ArrayList<>(leaves.size());
        for (var leaf : leaves) {
            if (leaf.componentProductUid() == null) {
                log.warn("BOM explosion: leaf has no uid (productId={}) for composed uid={} — skipping",
                        leaf.componentProductId(), composedProductUid);
                continue;
            }
            // Filter non-stockable: load the product to check (N+1 per BOM component — same as
            // the legacy path; the BOM leaf summary does not carry stockable flag yet).
            try {
                ProductDto compProduct = productService.getByUid(leaf.componentProductUid());
                if (!compProduct.stockable()) {
                    log.info("BOM explosion: skipping non-stockable leaf uid={} name='{}' " +
                                    "of composed product uid={} (BR-STOCK-04, D-7)",
                            leaf.componentProductUid(), leaf.componentProductName(),
                            composedProductUid);
                    continue;
                }
                result.add(new ExplosionLine(compProduct.id(), leaf.totalQuantity().negate()));
            } catch (Exception e) {
                log.warn("BOM explosion: could not load leaf product for uid={} — skipping ({})",
                        leaf.componentProductUid(), e.getMessage());
            }
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Legacy single-level path (original, unchanged — D-5 coexistence)
    // -------------------------------------------------------------------------

    private List<ExplosionLine> explodeLegacy(String composedProductUid, BigDecimal lineQtyInBase) {
        List<ProductComponentDto> components = productService.listComponents(composedProductUid);
        List<ExplosionLine> result = new ArrayList<>();

        for (ProductComponentDto comp : components) {
            ProductDto compProduct = productService.getByUid(comp.componentProductUid());
            if (!compProduct.stockable()) {
                log.info("Recipe explosion: skipping non-stockable component uid={} name='{}' " +
                                "of composed product uid={} (BR-STOCK-04, D-3)",
                        comp.componentProductUid(), comp.componentProductName(), composedProductUid);
                continue;
            }
            BigDecimal deduction = lineQtyInBase.multiply(comp.quantity()).negate();
            result.add(new ExplosionLine(compProduct.id(), deduction));
        }
        return result;
    }

}
