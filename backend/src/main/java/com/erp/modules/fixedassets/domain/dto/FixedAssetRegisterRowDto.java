package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One asset in the Fixed Asset Register as at a date (FR-FA-17).
 *
 * @param cost                    carrying cost as at the date (acquisition cost adjusted by any
 *                                revaluation dated on or before it)
 * @param accumulatedDepreciation posted depreciation charged for periods on or before the date
 * @param nbv                     cost − accumulated depreciation for an asset IN SERVICE at the date;
 *                                null for one that is not on the books then (draft, disposed,
 *                                written off) — it has no book value, which is not the same as zero
 * @param status                  the asset's status AS AT the date, not necessarily today's
 * @param inTotals                true when the asset was in service at the date and so counts in the
 *                                category and grand totals
 */
public record FixedAssetRegisterRowDto(
        String assetUid,
        String assetNumber,
        String name,
        String categoryCode,
        String categoryName,
        String branchName,
        String location,
        String costCentreName,
        LocalDate acquisitionDate,
        BigDecimal acquisitionCost,
        BigDecimal cost,
        BigDecimal accumulatedDepreciation,
        BigDecimal nbv,
        FixedAssetStatus status,
        LocalDate disposedAt,
        boolean inTotals
) {}
