package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.enums.StockTransferMode;
import com.erp.modules.stock.domain.enums.StockTransferStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for a stock transfer (ADR-0028 D-5).
 *
 * <p>sourceBranchName/sourceBranchCode/destBranchName/destBranchCode/sourceLocationName/
 * destLocationName are enrichment fields resolved at read time by the service (mirrors
 * SalesOrderDto's customer/branch enrichment) so a branch manager can see which branch/location a
 * transfer is between — only the internal ids travelled before.
 */
public record StockTransferDto(
        Long id,
        String uid,
        Long companyId,
        String transferNumber,
        StockTransferStatus status,
        StockTransferMode transferMode,
        Long sourceBranchId,
        String sourceBranchName,
        String sourceBranchCode,
        Long sourceLocationId,
        String sourceLocationName,
        Long destBranchId,
        String destBranchName,
        String destBranchCode,
        Long destLocationId,
        String destLocationName,
        LocalDate transferDate,
        LocalDate expectedArrivalDate,
        Instant dispatchedAt,
        Long dispatchedBy,
        Instant receivedAt,
        Long receivedBy,
        String notes,
        List<StockTransferLineDto> lines
) {}
