package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.ProductBulkPack;
import java.math.BigDecimal;
import java.util.List;

/**
 * Response DTO for a product bulk pack unit (FR-PROD-06).
 * unitUid/unitCode/unitName are enriched from the UnitOfMeasure association (UoM cutover).
 *
 * <p>{@code warnings} carries SOFT advisory messages raised while saving — the pack was still
 * created. It exists because the "a pack should normally contain more than one base unit"
 * guardrail was client-side only: a bulk import or a direct API call could store
 * {@code factorToBase = 0.0208333} (the real Kilimanjaro misconfiguration) and get a clean 201.
 * It is deliberately NOT a rejection: fractional factors are legitimate for weight/volume splits
 * (a 0.5 kg pack of a kg-based product), and a hard {@code >= 1} rule would contradict ADR-0007.
 * Always empty on read paths.
 */
public record ProductBulkPackDto(
        Long id,
        String uid,
        Long productId,
        String unitUid,
        String unitCode,
        String unitName,
        BigDecimal factorToBase,
        String barcode,
        boolean purchaseDefault,
        boolean saleDefault,
        List<String> warnings
) {

    /** Read projection — no warnings (they belong to the write that produced the row). */
    public static ProductBulkPackDto from(ProductBulkPack bp) {
        return from(bp, List.of());
    }

    public static ProductBulkPackDto from(ProductBulkPack bp, List<String> warnings) {
        return new ProductBulkPackDto(
                bp.getId(),
                bp.getUid(),
                bp.getProduct().getId(),
                bp.getUnit() != null ? bp.getUnit().getUid()  : null,
                bp.getUnit() != null ? bp.getUnit().getCode() : null,
                bp.getUnit() != null ? bp.getUnit().getName() : null,
                bp.getFactorToBase(),
                bp.getBarcode(),
                bp.isPurchaseDefault(),
                bp.isSaleDefault(),
                warnings == null ? List.of() : List.copyOf(warnings)
        );
    }
}
