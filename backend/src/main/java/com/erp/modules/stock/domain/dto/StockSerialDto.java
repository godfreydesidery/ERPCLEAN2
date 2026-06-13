package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.enums.SerialStatus;
import java.time.Instant;

/**
 * Response DTO for a stock serial number (ADR-0028 D-7, FR-INVD-27).
 */
public record StockSerialDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long locationId,
        Long productId,
        String serialNumber,
        SerialStatus serialStatus,
        String receivedDocumentUid,
        String issuedDocumentUid,
        Instant createdAt
) {}
