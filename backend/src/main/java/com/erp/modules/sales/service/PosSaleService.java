package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.PosSaleLookupDto;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;

/**
 * POS quick-sale: create a DIRECT SalesInvoice tagged to the session, resolve prices,
 * compute totals, and immediately finalise it (ADR-0029 D-5, FR-SD-15).
 */
public interface PosSaleService {

    /**
     * Backward-compatible overload — no idempotency key (existing callers / tests).
     * Delegates to {@link #processSale(String, PosSaleRequest)} with a {@code null} key.
     */
    default SalesInvoiceDto processSale(PosSaleRequest request) {
        return processSale(null, request);
    }

    /**
     * Process a POS sale — creates and finalises an invoice in one transaction.
     *
     * <p><b>K11 ordering rule.</b> The idempotency marker is consulted FIRST — before the session,
     * stock, pricing and policy guards. A definitive "yes, this already posted" has to be answerable
     * whatever state the till is in now; when the OPEN-session check ran first, the stored answer
     * became permanently unreachable the moment the shift ended and the client looped forever on the
     * unfinished-sale dialog.
     *
     * @param idempotencyKey optional client {@code Idempotency-Key} (nullable/blank = no dedup).
     *                       A repeat with the same key, per company, returns the ORIGINAL invoice
     *                       instead of creating a second sale (ADR-0042 D-1) — safe to retry an
     *                       ambiguous POST.
     * @return the finalised invoice DTO (for receipt printing)
     * @throws com.erp.modules.sales.domain.exception.PosSaleFlowException with a machine-readable
     *         {@code IN_FLIGHT} / {@code REJECTED} / {@code STALE_REPLAY} status, so the caller never
     *         has to guess from the message which of the three happened
     */
    SalesInvoiceDto processSale(String idempotencyKey, PosSaleRequest request);

    /**
     * Answer "did the sale I sent under this reference actually post?" without re-running the sale.
     *
     * <p>Reads only the idempotency marker for the caller's active company, so it stays cheap and
     * stays correct after the session has closed. Returns POSTED (with the invoice reference),
     * NEVER_POSTED, or a genuine UNKNOWN — never an exception standing in for an answer.
     *
     * @param idempotencyKey the key the sale was (or would have been) sent with
     * @return the verdict; never {@code null}
     */
    PosSaleLookupDto lookupByIdempotencyKey(String idempotencyKey);

    /**
     * Reverse (void) a POS sale at the till (ADR-0042 D-2). Delegates to the existing invoice void —
     * which reverses revenue/VAT/<b>cash</b> (the cash sale's {@code SALES_REVERSAL}) and stock — and
     * enforces the POS rules: the invoice must be a POS sale and its session must still be OPEN (so
     * the drawer absorbs the refund). A settled/closed session is a back-office void, not a till
     * reversal.
     *
     * <p><b>Authorisation (K1 follow-up).</b> Two rules on top of the POS/session ones, because
     * neither was enforced and the till's manager prompt existed only in the client:
     * <ul>
     *   <li>a caller may reverse only sales rung on their OWN session, unless they hold
     *       {@code SALES.INVOICE.VOID} — reaching into a stranger's open drawer is not a cashier's
     *       authority;</li>
     *   <li>a caller without {@code SALES.INVOICE.VOID} must supply
     *       {@link VoidInvoiceRequest#authorisedByUid()} from a successful manager step-up, which is
     *       re-verified server-side against a real, active, different user who holds that code in
     *       this invoice's company.</li>
     * </ul>
     * Both refusals are 403 with a friendly message, and both are audited independently of the
     * rolled-back transaction.
     *
     * @param invoiceUid the POS sale invoice to reverse
     * @param request    the reason (recorded on the void + audit) and the manager approval, if any
     */
    void reverseSale(String invoiceUid, VoidInvoiceRequest request);

    /**
     * Convenience overload for internal callers and tests that carry no manager approval — a caller
     * who already holds {@code SALES.INVOICE.VOID} needs none.
     */
    default void reverseSale(String invoiceUid, String reason) {
        reverseSale(invoiceUid, new VoidInvoiceRequest(reason));
    }
}
