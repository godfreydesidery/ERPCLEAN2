package com.erp.modules.purchases.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Everything the printed purchase return / debit note needs, resolved in the purchases module so
 * the documents module and the export controller copy figures rather than derive them
 * (BR-DOC-02 / BR-DOC-09) — the same split as {@link GoodsReceiptPrintDto}.
 *
 * <p>Stream-only: this model backs {@code GET /purchase-returns/uid/{uid}/export}, which renders on
 * request and stores nothing (no {@code generated_documents} row, no schema change).
 *
 * @param returnDate           the return's business date — {@code confirmed_at} once confirmed,
 *                             else {@code created_at} — formatted {@code dd-MMM-yyyy} in the
 *                             company's own time zone, never a raw ISO instant
 * @param returnedAt           the instant behind {@code returnDate}
 * @param goodsReceiptNumber   the GRN the goods came in on
 * @param purchaseOrderNumber  the PO behind that GRN; null for a direct (PO-less) receipt
 * @param supplierAddressLines whatever address the supplier master holds, blank parts omitted
 * @param debitNoteNumber      the AP debit note raised on confirm; null while the return is DRAFT
 * @param preparedByName       the display name of the user who raised the return
 * @param netAmount            stored net value of the return, 2 dp
 * @param vatAmount            stored VAT, 2 dp — zero on every return raised today
 * @param totalAmount          stored gross, 2 dp
 * @param timeZone             the company's zone id — for the "Printed On" footprint
 */
public record PurchaseReturnPrintDto(
        String  uid,
        Long    companyId,
        String  returnNumber,
        String  status,
        String  returnDate,
        Instant returnedAt,
        String  goodsReceiptNumber,
        String  purchaseOrderNumber,
        String  branchName,
        String  supplierName,
        String  supplierTin,
        String  supplierVrn,
        List<String> supplierAddressLines,
        String  reason,
        String  currency,
        String  debitNoteNumber,
        String  preparedByName,
        List<PurchaseReturnPrintLineDto> lines,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal totalAmount,
        String  timeZone
) {

    public PurchaseReturnPrintDto {
        supplierAddressLines = supplierAddressLines != null ? List.copyOf(supplierAddressLines) : List.of();
        lines = lines != null ? List.copyOf(lines) : List.of();
    }

    /** True when the return carries VAT — the VAT row is only printed then. */
    public boolean hasVat() {
        return vatAmount != null && vatAmount.signum() != 0;
    }

    /** A DRAFT return has not left stock or raised a debit note yet; the print says so. */
    public boolean isDraft() {
        return "DRAFT".equals(status);
    }
}
