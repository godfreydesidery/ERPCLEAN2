package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ApplyCreditNoteRequest;
import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ArCreditNoteService {

    /**
     * Raise a credit note. Posts FULL contra to GL once at raise (DR Revenue/VAT / CR AR-control).
     * If the request targets an invoice, immediately auto-applies the full CN to that invoice
     * (raise + apply in one TX — existing callers see the same net end-state as before).
     * (FR-AR-14, OQ-AR-04, ADR-0040 D-6)
     */
    ArCreditNoteDto raise(RaiseCreditNoteRequest req);

    /**
     * Apply a previously UNAPPLIED or PARTIAL credit note to one or more open invoices.
     * Decrements invoice outstanding_amount, decrements CN unapplied_amount, updates status.
     * Posts NOTHING to GL except a realized-FX plug per allocation when rates differ.
     * SELECT FOR UPDATE enforces the Σ invariant (ADR-0040 D-6).
     */
    ArCreditNoteDto apply(ApplyCreditNoteRequest req);

    /**
     * Replace the current allocation set with a new one (delete-and-reinsert).
     * Restores outstanding on previously allocated invoices before re-applying.
     * Posts no AR/Revenue legs — only FX plug when rates differ (same as apply).
     */
    ArCreditNoteDto reapply(ApplyCreditNoteRequest req);

    ArCreditNoteDto getByUid(String uid);

    Page<ArCreditNoteDto> listByCompany(Long companyId, Pageable pageable);

    /**
     * SAL-03: clears the AR open item of a voided credit sale with a {@code SALE_VOID} credit note
     * raised and applied for its whole outstanding balance. Posts NOTHING to GL — the void already
     * reversed the sale's journal entry (SaleVoidingHandler), AR control included — and settles at
     * the open item's own rate, so no realized-FX plug arises either. Runs in its own transaction
     * (called from an outbox handler). Idempotent: a second call for the same sale is a no-op.
     *
     * @param invoiceGross the sale's gross total, used with {@code invoiceVat} only to split the
     *                     note's amount into net and VAT for its printed face (nullable)
     * @return the credit note uid, or empty when there is nothing to clear (cash sale, no open
     *         item, already cleared, or nothing outstanding)
     */
    java.util.Optional<String> raiseForSaleVoid(Long companyId, String salesInvoiceUid,
                                                String invoiceNumber, java.time.LocalDate noteDate,
                                                java.math.BigDecimal invoiceGross,
                                                java.math.BigDecimal invoiceVat);
}
