package com.erp.modules.costing.domain.dto;

import com.erp.modules.costing.domain.enums.DimensionSlot;
import java.util.List;

/**
 * The dimension-sliced trial balance response (ADR-0025 D-8, FR-CC-14).
 * Note: the slice does NOT net to zero — BR-CC-01.
 */
public record DimensionSlicedTbDto(
        DimensionSlot dimensionSlot,
        boolean rollUp,
        List<DimensionSlicedTbRowDto> rows
) {}
