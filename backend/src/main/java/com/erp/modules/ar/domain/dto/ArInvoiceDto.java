package com.erp.modules.ar.domain.dto;

import com.erp.modules.ar.domain.enums.ArInvoiceSource;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/** Response DTO for an AR open item. Long ids serialise as JSON strings globally. */
public record ArInvoiceDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long customerId,
        ArInvoiceSource source,
        String sourceInvoiceUid,
        String documentNo,
        BigDecimal originalAmount,
        BigDecimal outstandingAmount,
        String currency,
        LocalDate invoiceDate,
        LocalDate dueDate,
        ArInvoiceStatus status
) {}
