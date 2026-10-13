package com.erp.modules.stock.service;

import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.service.ProductService;
import java.math.BigDecimal;
import org.springframework.stereotype.Component;

/**
 * Converts a quantity typed in one of a product's units into BASE units (STK-08 / OPN-01).
 *
 * <p>Shared by the manual stock screens — adjustment, count entry and opening balance — so a
 * storekeeper can type "4 cartons" instead of multiplying by the pack size in his head. Stock is
 * always posted in base units; the unit only changes how the operator states the quantity.
 *
 * <p>Same rule as {@code StockTransferServiceImpl.resolveUnit} (and the sales invoice): a null or
 * blank unit, or the base unit's own uid, means the base unit — so every caller that predates this
 * keeps its meaning. Any other uid must be one of the product's configured bulk packs; an
 * unrecognised unit is REFUSED, never defaulted to base — defaulting would accept "2 cartons" and
 * move 2 pieces, and nobody would notice until a count came up short.
 */
@Component
public class StockUnitResolver {

    /** The unit a quantity was stated in and how many base units one of it is. */
    public record Uom(String unitUid, String name, BigDecimal factorToBase) {}

    private final ProductService productService;

    public StockUnitResolver(ProductService productService) {
        this.productService = productService;
    }

    /**
     * @throws IllegalArgumentException (friendly) when the unit is neither the base unit nor one of
     *         the product's pack sizes
     */
    public Uom resolve(ProductDto product, String unitUid) {
        if (unitUid == null || unitUid.isBlank() || unitUid.trim().equals(product.baseUnitUid())) {
            return new Uom(null, product.baseUnitName(), BigDecimal.ONE);
        }
        String wanted = unitUid.trim();
        return productService.listBulkPacks(product.uid()).stream()
                .filter(bp -> wanted.equals(bp.unitUid()))
                .filter(bp -> bp.factorToBase() != null && bp.factorToBase().signum() > 0)
                .findFirst()
                .map(bp -> new Uom(bp.unitUid(), bp.unitName(), bp.factorToBase()))
                .orElseThrow(() -> new IllegalArgumentException(
                        "That unit cannot be used for " + product.name()
                        + ". Choose the item's own unit or one of its pack sizes."));
    }

    /** {@code qty} stated in {@code unitUid}, expressed in the product's base unit. */
    public BigDecimal toBase(ProductDto product, BigDecimal qty, String unitUid) {
        if (qty == null) {
            return null;
        }
        Uom uom = resolve(product, unitUid);
        return uom.factorToBase().compareTo(BigDecimal.ONE) == 0 ? qty : qty.multiply(uom.factorToBase());
    }
}
