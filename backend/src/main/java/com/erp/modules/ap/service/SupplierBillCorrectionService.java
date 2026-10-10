package com.erp.modules.ap.service;

/**
 * Corrections to a supplier bill that never reached the ledger (AP-01).
 *
 * <p>A bill that the 3-way match put on hold, or whose match failed (no FX rate, closed period),
 * has no GL posting, no payment and no debit note behind it. Until this existed it could not be
 * edited, deleted or entered again — the duplicate-invoice guard still saw it — so it was stranded.
 */
public interface SupplierBillCorrectionService {

    /**
     * Deletes a DRAFT or HELD bill that has no GL posting, no payment, no debit note and no
     * landed-cost charge pointing at it. Its lines and match rows are removed with it, so the same
     * supplier invoice number can be entered again. A posted bill must be corrected with a debit
     * note instead. AP.BILL.ENTER.
     */
    void deleteUnposted(String billUid);
}
