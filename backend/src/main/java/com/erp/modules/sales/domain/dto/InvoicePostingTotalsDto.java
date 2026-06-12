package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * The monetary facts GL's SalesPostingHandler and AR's ArSalePostedHandler need when
 * re-reading a finalised invoice (ADR-0013 D-6/D-12, ADR-0014 D-10).
 * Sales-owned DTO; GL and AR import this, never a Sales entity (NFR-AR-06).
 *
 * <p>isCashSale: derives from customer_kind (CASH_WALK_IN=true) or paid-in-full for credit
 * customers (ADR-0014 D-10). When false, GL DRs ACCOUNTS_RECEIVABLE and AR creates an open item.
 *
 * <p>outstandingAmount: the unpaid residual (gross − Σ payments). For a fully-unpaid credit sale
 * this equals grossTotalAmount. AR uses this as the open item amount (D-10).
 *
 * <p>ADR-0025 D-6: {@code costCentreValueId} and {@code departmentValueId} are the invoice's
 * header-level dimension defaults (scalar ids, nullable — null = untagged). SalesPostingHandler
 * stamps the revenue LineDrafts with these ids.
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
        Instant finalisedAt,
        /** Unpaid residual at finalise — the AR open-item amount (ADR-0014 D-10). */
        BigDecimal outstandingAmount,
        // --- cost-centre (ADR-0025 D-6) ---
        /** Cost Centre dimension default id (nullable — untagged when null). */
        Long costCentreValueId,
        /** Department dimension default id (nullable — untagged when null). */
        Long departmentValueId,
        // --- projects (ADR-0033 D-4c) ---
        /** Project id (nullable — untagged when null). Threaded onto the revenue GL leg. */
        Long projectId,
        /** Project task id (nullable). */
        Long projectTaskId
) {}
