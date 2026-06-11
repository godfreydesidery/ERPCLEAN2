package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.enums.StockTransferStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for a stock transfer (ADR-0028 D-5).
 */
public record StockTransferDto(
        Long id,
        String uid,
        Long companyId,
        String transferNumber,
        StockTransferStatus status,
        String transferMode,
        Long sourceBranchId,
        Long sourceLocationId,
        Long destBranchId,
        Long destLocationId,
        LocalDate transferDate,
        Instant dispatchedAt,
        Instant receivedAt,
        String notes,
        List<StockTransferLineDto> lines
) {}
