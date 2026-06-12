package com.erp.modules.fixedassets.domain.dto;

import java.math.BigDecimal;

/** One asset's planned charge in the depreciation run preview (ADR-0030 D-4). */
public record DepreciationRunPreviewLineDto(
        Long fixedAssetId,
        String fixedAssetUid,
        String assetNumber,
        String assetName,
        Long categoryId,
        int periodSeq,
        BigDecimal plannedCharge
) {}
