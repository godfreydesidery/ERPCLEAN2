package com.erp.modules.fixedassets.domain.dto;

import java.math.BigDecimal;

/**
 * Register totals for one category (or, with null code/name, the whole register) — summed over the
 * assets IN SERVICE at the as-at date only, the same population the FA-to-GL reconciliation sums.
 */
public record FixedAssetRegisterTotalDto(
        String categoryCode,
        String categoryName,
        int assetCount,
        BigDecimal cost,
        BigDecimal accumulatedDepreciation,
        BigDecimal nbv
) {}
