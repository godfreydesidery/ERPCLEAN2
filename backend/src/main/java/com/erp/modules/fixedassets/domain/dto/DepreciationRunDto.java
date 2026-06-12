package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.DepreciationRunStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

public record DepreciationRunDto(
        Long id,
        String uid,
        Long companyId,
        String runNumber,
        Long fiscalPeriodId,
        LocalDate postingDate,
        DepreciationRunStatus status,
        BigDecimal totalChargeAmount,
        int assetCount,
        String glEntryUid,
        String currency,
        Instant executedAt,
        List<DepreciationRunLineDto> lines
) {}
