package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The monetary facts GL's SalesPostingHandler needs when it re-reads a finalised invoice
 * (ADR-0013 D-6/D-12). Sales-owned DTO; GL imports this, never a Sales entity.
 *
 * <p>isCashSale: v1 sales are paid-in-full at finalise (ADR-0008 D-8), so this is always true
 * until credit-sale support lands in the AR increment (T1.2). GL DRs Cash for v1.
 */
public record InvoicePostingTotalsDto(
        String invoiceUid,
        String status,
        String currency,
        Long customerId,
        boolean isCashSale,
        BigDecimal netTotalAmount,
        BigDecimal vatTotalAmount,
        BigDecimal grossTotalAmount,
        Instant finalisedAt
) {}
