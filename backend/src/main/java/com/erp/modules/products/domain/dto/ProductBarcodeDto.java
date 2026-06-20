package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductBarcode;
import com.erp.modules.products.domain.enums.BarcodeType;
import com.erp.modules.products.domain.enums.SymbologyValueKind;
import java.math.BigDecimal;

/**
 * Response DTO for a product barcode (FR-PROD-08).
 * The three {@code derived*} fields are null on exact-match lookups (backward-compatible)
 * and populated only when the barcode was resolved via an embedded-weight/price rule
 * (ADR-0044 D-1a / BR-9).
 */
public record ProductBarcodeDto(
        Long id,
        String uid,
        Long productId,
        Long companyId,
        String barcode,
        BarcodeType barcodeType,
        Long uomId,
        boolean primary,
        /** Decoded quantity (e.g. weight in kg) — null unless this was an embedded-weight lookup. */
        BigDecimal derivedQuantity,
        /** Decoded amount (e.g. embedded price) — null unless this was an embedded-price lookup. */
        BigDecimal derivedAmount,
        /** What the decoded embedded field represents — null for plain exact-match lookups. */
        SymbologyValueKind valueKind
) {

    /** Factory for a plain barcode row (exact-match path). Derived fields are null. */
    public static ProductBarcodeDto from(ProductBarcode b) {
        return new ProductBarcodeDto(
                b.getId(),
                b.getUid(),
                b.getProduct().getId(),
                b.getCompanyId(),
                b.getBarcode(),
                b.getBarcodeType(),
                b.getUomId(),
                b.isPrimary(),
                null,
                null,
                null
        );
    }

    /**
     * Factory for an embedded-barcode decode result.
     * Base fields come from the resolved {@link ProductBarcode}; derived fields from the decode.
     */
    public static ProductBarcodeDto fromDecode(ProductBarcode b, EmbeddedBarcodeDecode decode) {
        BigDecimal derivedQty    = decode.valueKind() == SymbologyValueKind.WEIGHT ? decode.value() : null;
        BigDecimal derivedAmount = decode.valueKind() == SymbologyValueKind.PRICE  ? decode.value() : null;
        return new ProductBarcodeDto(
                b.getId(),
                b.getUid(),
                b.getProduct().getId(),
                b.getCompanyId(),
                b.getBarcode(),
                b.getBarcodeType(),
                b.getUomId(),
                b.isPrimary(),
                derivedQty,
                derivedAmount,
                decode.valueKind()
        );
    }
}
