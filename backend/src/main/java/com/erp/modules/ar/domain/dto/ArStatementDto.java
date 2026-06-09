package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** Customer statement — open items + ageing + recent receipts as at a date (ADR-0014 D-7). */
public record ArStatementDto(
        Long companyId,
        Long customerId,
        LocalDate asAt,
        BigDecimal totalOutstanding,
        String currency,
        List<ArAgeingRowDto> ageing,
        List<ArInvoiceDto> openItems,
        List<ArReceiptDto> recentReceipts
) {}
