package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for a supplier bill.
 * D-7: exposes whtTypeId / whtTaxableBase / whtAmount snapshot set at bill entry.
 */
public record SupplierBillDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long supplierId,
        String billNumber,
        String supplierInvoiceNo,
        SupplierBillSource source,
        String purchaseOrderUid,
        LocalDate billDate,
        LocalDate dueDate,
        // P2: tax-point + received dates
        LocalDate taxPointDate,
        LocalDate receivedDate,
        // P2 D1: payment terms + settlement discount (data-only)
        Long paymentTermsId,
        LocalDate settlementDiscountDueDate,
        BigDecimal settlementDiscountAmount,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        BigDecimal outstandingAmount,
        String currency,
        SupplierBillStatus status,
        String postedGlEntryUid,
        // D-7: WHT plan snapshot
        Long whtTypeId,
        BigDecimal whtTaxableBase,
        BigDecimal whtAmount,
        List<SupplierBillLineDto> lines
) {}
