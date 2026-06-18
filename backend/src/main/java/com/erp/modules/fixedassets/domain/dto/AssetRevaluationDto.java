package com.erp.modules.fixedassets.domain.dto;

import com.erp.modules.fixedassets.domain.enums.RevaluationDirection;
import java.math.BigDecimal;
import java.time.LocalDate;

public record AssetRevaluationDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long fixedAssetId,
        LocalDate revaluationDate,
        Long fiscalPeriodId,
        RevaluationDirection direction,
        BigDecimal deltaAmount,
        BigDecimal carryingBefore,
        BigDecimal carryingAfter,
        String glEntryUid,
        String currency,
        String reason,
        // P2 D7 — valuer + approval provenance
        String valuerName,
        String valuationRef,
        Long approvedBy
) {}
