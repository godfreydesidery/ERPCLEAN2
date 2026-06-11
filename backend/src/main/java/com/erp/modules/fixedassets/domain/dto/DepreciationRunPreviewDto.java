package com.erp.modules.fixedassets.domain.dto;

import java.math.BigDecimal;
import java.util.List;

/** Preview of the depreciation run for a period — read-only, nothing posted (ADR-0030 D-4). */
public record DepreciationRunPreviewDto(
        Long companyId,
        String fiscalPeriodUid,
        int assetCount,
        BigDecimal totalChargeAmount,
        List<DepreciationRunPreviewLineDto> lines
) {}
