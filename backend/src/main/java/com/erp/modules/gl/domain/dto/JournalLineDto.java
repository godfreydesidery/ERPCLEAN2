package com.erp.modules.gl.domain.dto;

import java.math.BigDecimal;

/** Response DTO for a journal line. */
public record JournalLineDto(
        Long id,
        String uid,
        int lineNo,
        Long accountId,
        String accountCode,
        String accountName,
        BigDecimal debitAmount,
        BigDecimal creditAmount,
        String currency,
        String lineMemo,
        /** Informational tax code tag (P2-M1); null when not set. */
        String taxCode,
        /** Informational tax amount (P2-M1); null when not set. */
        BigDecimal taxAmount
) {}
