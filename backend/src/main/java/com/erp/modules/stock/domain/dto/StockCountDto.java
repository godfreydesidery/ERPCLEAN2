package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.enums.StockCountStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for a stock count document (ADR-0028 D-6).
 */
public record StockCountDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String countNumber,
        StockCountStatus status,
        String countType,
        Long locationId,
        LocalDate countDate,
        Instant frozenAt,
        Instant postedAt,
        Instant cancelledAt,
        String varianceGlEntryUid,
        String notes,
        List<StockCountLineDto> lines
) {}
