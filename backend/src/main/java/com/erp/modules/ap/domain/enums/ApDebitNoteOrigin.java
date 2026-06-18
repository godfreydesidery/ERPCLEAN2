package com.erp.modules.ap.domain.enums;

/**
 * Why an AP debit note was raised (P2 clean-origin support; mirrors AR's ArCreditNoteOrigin).
 *
 * <p>Historically the kind was carried as a free-text {@code KIND:{uid}} prefix in
 * {@code ApDebitNote.origin}; this enum isolates the kind so the source-document reference can live
 * in the dedicated {@code origin_ref} column.
 *
 * <ul>
 *   <li>STANDALONE — manually raised against a supplier bill (or general supplier credit).</li>
 *   <li>PURCHASE_RETURN — raised on behalf of a purchase return (ADR-0027 D-7); the returned
 *       purchase-return uid is carried in {@code origin_ref}.</li>
 * </ul>
 *
 * <p><b>Field mapping deferred (P2 straggler):</b> {@code ApDebitNote.origin} is intentionally left as
 * the free-text column for now. Re-typing it to this enum requires changing
 * {@code RaiseDebitNoteRequest.origin} and the purchases-module caller (PurchaseReturnServiceImpl) to
 * split the combined value into (kind, ref) — both outside the AP/cashbank scope of this wave.
 */
public enum ApDebitNoteOrigin {
    STANDALONE,
    /** Raised on behalf of a purchase return (ADR-0027 D-7); ref uid in {@code origin_ref}. */
    PURCHASE_RETURN
}
