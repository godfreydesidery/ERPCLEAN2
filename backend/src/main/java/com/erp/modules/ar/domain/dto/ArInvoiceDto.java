package com.erp.modules.ar.domain.dto;

import com.erp.modules.ar.domain.enums.ArInvoiceSource;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Response DTO for an AR open item. Long ids serialise as JSON strings globally.
 *
 * <p>{@code customerUid}, {@code customerCode} and {@code customerName} are read-time fills (the
 * open item stores only the numeric customer id): screens show the customer by name and act on it
 * by uid without preloading a customer list. They are null where a caller builds the DTO without
 * the fill.
 */
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
        ArInvoiceStatus status,
        String customerUid,
        String customerCode,
        String customerName
) {
    /** The original shape, without the customer fill. */
    public ArInvoiceDto(Long id, String uid, Long companyId, Long branchId, Long customerId,
                        ArInvoiceSource source, String sourceInvoiceUid, String documentNo,
                        BigDecimal originalAmount, BigDecimal outstandingAmount, String currency,
                        LocalDate invoiceDate, LocalDate dueDate,
                        LocalDate settlementDiscountDueDate, BigDecimal settlementDiscountAmount,
                        ArInvoiceStatus status) {
        this(id, uid, companyId, branchId, customerId, source, sourceInvoiceUid, documentNo,
                originalAmount, outstandingAmount, currency, invoiceDate, dueDate,
                settlementDiscountDueDate, settlementDiscountAmount, status, null, null, null);
    }

    /** A copy carrying {@code number} as its document number (read-time fill for older items). */
    public ArInvoiceDto withDocumentNo(String number) {
        return new ArInvoiceDto(id, uid, companyId, branchId, customerId, source, sourceInvoiceUid,
                number, originalAmount, outstandingAmount, currency, invoiceDate, dueDate,
                settlementDiscountDueDate, settlementDiscountAmount, status,
                customerUid, customerCode, customerName);
    }

    /** A copy carrying the customer's uid, code and display name (read-time fill). */
    public ArInvoiceDto withCustomer(String custUid, String custCode, String custName) {
        return new ArInvoiceDto(id, uid, companyId, branchId, customerId, source, sourceInvoiceUid,
                documentNo, originalAmount, outstandingAmount, currency, invoiceDate, dueDate,
                settlementDiscountDueDate, settlementDiscountAmount, status,
                custUid, custCode, custName);
    }
}
