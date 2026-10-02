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
        /** The first of the run's journals (kept for existing links; see {@link #glEntryUids}). */
        String glEntryUid,
        /**
         * Every journal the run posted - one per asset branch (an asset's depreciation is booked to
         * the asset's branch). Runs posted before the per-branch split carry their single journal.
         */
        List<String> glEntryUids,
        String currency,
        Instant executedAt,
        List<DepreciationRunLineDto> lines
) {}
