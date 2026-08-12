package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.BillComparisonState;
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
 *
 * <p>UAT 2026-08-12: {@code comparisonState} says how much of the bill was actually checked against
 * a purchase order and a goods receipt. Also derived at read time, from the {@code bill_match} rows.
 * It exists because a bill can carry a real payable, read MATCHED and post to the ledger without any
 * comparison having run — a service charge with no purchase link, or an opening balance the match
 * engine never saw. Neither is wrong, but both must be findable rather than indistinguishable from a
 * bill somebody checked.
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
        // UAT 2026-08-12: derived, never stored — see the class javadoc.
        BillComparisonState comparisonState,
        List<SupplierBillLineDto> lines
) {}
