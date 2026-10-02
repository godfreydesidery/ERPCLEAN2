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
        // P2 D1: settlement discount (data-only, inherited from the source SI's payment terms)
        LocalDate settlementDiscountDueDate,
        BigDecimal settlementDiscountAmount,
        ArInvoiceStatus status
) {
    /** A copy carrying {@code number} as its document number (read-time fill for older items). */
    public ArInvoiceDto withDocumentNo(String number) {
        return new ArInvoiceDto(id, uid, companyId, branchId, customerId, source, sourceInvoiceUid,
                number, originalAmount, outstandingAmount, currency, invoiceDate, dueDate,
                settlementDiscountDueDate, settlementDiscountAmount, status);
    }
}
