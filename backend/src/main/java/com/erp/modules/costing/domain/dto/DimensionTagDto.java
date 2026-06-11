package com.erp.modules.costing.domain.dto;

/**
 * A bundle of optional dimension value uids a poster / document carries before resolution
 * (ADR-0025 D-1, D-5). All fields nullable — null = untagged for that slot.
 *
 * <p>Posters pass this to {@link com.erp.modules.costing.service.DimensionResolver#resolveTag}
 * to obtain the resolved {@link ResolvedDimensionTagDto} (with Long ids) before building a
 * {@code LineDraft}.
 */
public record DimensionTagDto(
        String costCentreValueUid,
        String departmentValueUid,
        String dimension3ValueUid,
        String dimension4ValueUid
) {
    /** Convenience factory: a fully untagged bundle. */
    public static DimensionTagDto empty() {
        return new DimensionTagDto(null, null, null, null);
    }
}
