package com.erp.modules.products.domain.dto;

import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.enums.BomStatus;
import java.math.BigDecimal;
import java.util.List;

/**
 * Full BOM header response DTO (ADR-0026 D-1, API surface).
 * Both {@code id} (serialised as JSON string via global Long-as-string config) and {@code uid}.
 */
public record BomDto(
        Long id,
        String uid,
        Long companyId,
        Long parentProductId,
        String parentProductUid,
        Integer versionNo,
        BomStatus status,
        BigDecimal outputQty,
        BigDecimal yieldPercent,
        String effectiveFrom,
        String effectiveTo,
        String sourceBomUid,
        String notes,
        String activatedAt,
        String archivedAt,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy,
        List<BomComponentDto> components
) {

    public static BomDto from(Bom b, String parentProductUid, List<BomComponentDto> components) {
        return new BomDto(
                b.getId(),
                b.getUid(),
                b.getCompanyId(),
                b.getParentProductId(),
                parentProductUid,
                b.getVersionNo(),
                b.getStatus(),
                b.getOutputQty(),
                b.getYieldPercent(),
                b.getEffectiveFrom() != null ? b.getEffectiveFrom().toString() : null,
                b.getEffectiveTo()   != null ? b.getEffectiveTo().toString()   : null,
                b.getSourceBomUid(),
                b.getNotes(),
                b.getActivatedAt() != null ? b.getActivatedAt().toString() : null,
                b.getArchivedAt()  != null ? b.getArchivedAt().toString()  : null,
                b.getVersion(),
                b.getCreatedAt() != null ? b.getCreatedAt().toString() : null,
                b.getCreatedBy(),
                b.getUpdatedAt() != null ? b.getUpdatedAt().toString() : null,
                b.getUpdatedBy(),
                components
        );
    }

    /** Header-only variant (no components list). */
    public static BomDto headerOnly(Bom b, String parentProductUid) {
        return from(b, parentProductUid, List.of());
    }
}
