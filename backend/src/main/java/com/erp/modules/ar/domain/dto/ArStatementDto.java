package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Customer statement — open items + ageing + recent receipts as at a date (ADR-0014 D-7).
 *
 * <p>{@code totalOutstanding} is in {@code currency} (the company base) and covers the
 * base-currency open items only. {@code totalsByCurrency} carries the outstanding per currency,
 * base first, then any foreign currency the customer owes in; {@code ageing} has one five-bucket
 * block per currency. Amounts in different currencies are never added together.
 */
public record ArStatementDto(
        Long companyId,
        Long customerId,
        LocalDate asAt,
        BigDecimal totalOutstanding,
        String currency,
        List<ArAgeingRowDto> ageing,
        List<ArInvoiceDto> openItems,
        List<ArReceiptDto> recentReceipts,
        Map<String, BigDecimal> totalsByCurrency
) {}
