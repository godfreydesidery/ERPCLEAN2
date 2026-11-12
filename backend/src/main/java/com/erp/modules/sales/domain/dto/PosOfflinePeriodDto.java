package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.OfflinePeriodReason;
import java.time.Instant;

/** One stretch of time a till sold offline (ADR-0068 D-8), as the review screen lists it. */
public record PosOfflinePeriodDto(
        String uid,
        String tillName,
        OfflinePeriodReason reason,
        String switchedByName,
        Instant startedAt,
        Instant endedAt,
        int salesCount
) {}
