package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.DirectReceiptRatificationState;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for a supplier bill.
 * D-7: exposes whtTypeId / whtTaxableBase / whtAmount snapshot set at bill entry.
 *
 * <p>K3 follow-up: {@code directReceiptRatification} surfaces the post-hoc ratification state of the
 * backing purchase order when the goods were received without an LPO. It is derived at read time
 * (never stored) and exists so an AP clerk can see, on the bill itself, that payment will be refused
 * until a manager ratifies the delivery — rather than discovering it at payment time.
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
        // K3 follow-up: derived, never stored — see the class javadoc.
        DirectReceiptRatificationState directReceiptRatification,
        List<SupplierBillLineDto> lines
) {}
