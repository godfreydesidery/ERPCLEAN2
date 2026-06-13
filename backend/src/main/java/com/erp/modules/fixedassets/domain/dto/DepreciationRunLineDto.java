package com.erp.modules.fixedassets.domain.dto;

import java.math.BigDecimal;

public record DepreciationRunLineDto(
        Long id,
        String uid,
        Long fixedAssetId,
        Long scheduleLineId,
        BigDecimal chargeAmount,
        BigDecimal accumDepAfter,
        BigDecimal nbvAfter
) {}
