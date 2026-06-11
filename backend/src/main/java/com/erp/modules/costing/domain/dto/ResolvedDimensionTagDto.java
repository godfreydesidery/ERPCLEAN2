package com.erp.modules.costing.domain.dto;

/**
 * A resolved (uid → id) dimension tag bundle returned by
 * {@link com.erp.modules.costing.service.DimensionResolver#resolveTag} (ADR-0025 D-5).
 * All ids are nullable — null = untagged for that slot.
 * Passed directly onto each {@code LineDraft} via the new optional dimension-id fields.
 */
public record ResolvedDimensionTagDto(
        Long costCentreValueId,
        Long departmentValueId,
        Long dimension3ValueId,
        Long dimension4ValueId
) {
    /** Convenience factory: a fully untagged resolved bundle. */
    public static ResolvedDimensionTagDto empty() {
        return new ResolvedDimensionTagDto(null, null, null, null);
    }
}
