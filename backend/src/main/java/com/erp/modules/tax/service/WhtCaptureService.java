package com.erp.modules.tax.service;

import com.erp.modules.tax.domain.dto.WhtCaptureResultDto;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Captures a WHT certificate when an AP payment or AR receipt is made with withholding (ADR-0017 D-9).
 * Does NOT post GL itself — returns the GL account id so the caller can append the WHT leg to its
 * own JournalEntryDraft.
 */
public interface WhtCaptureService {

    /**
     * Capture WHT on outgoing AP payment (kind = WHT_ON_PAYMENT). Resolves WHT_PAYABLE GL account.
     *
     * @param companyId       owning company
     * @param branchId        originating branch (nullable)
     * @param whtTypeUid      uid of the WhtType (must be same company, active, kind=WHT_ON_PAYMENT)
     * @param partySupplierId supplier id (party_id in wht_transactions)
     * @param partyName       supplier display name
     * @param partyTin        supplier TIN snapshot (nullable — from PartyBase.tin)
     * @param apPaymentUid    source_ref for the certificate
     * @param taxableBase     gross taxable amount before withholding
     * @param whtAmount       withheld amount
     * @param currency        ISO-4217 currency code
     * @param certificateDate date on the certificate
     * @param journalEntryUid the GL journal entry uid to link back (may be set after capture)
     * @param actorId         performing user id
     * @return WhtCaptureResultDto with glAccountId (WHT_PAYABLE), whtTransactionUid, certificateNumber
     */
    WhtCaptureResultDto captureOnPayment(Long companyId, Long branchId,
                                         String whtTypeUid,
                                         Long partySupplierId, String partyName,
                                         String partyTin,
                                         String apPaymentUid,
                                         BigDecimal taxableBase, BigDecimal whtAmount,
                                         String currency, LocalDate certificateDate,
                                         String journalEntryUid, Long actorId);

    /**
     * Back-link the GL journal entry uid to the WHT transaction after the entry has been posted.
     * Called by AR/AP services immediately after glPosting.post() succeeds.
     */
    void linkJournalEntry(String whtTransactionUid, String journalEntryUid);

    /**
     * Capture WHT on incoming AR receipt (kind = WHT_ON_RECEIPT). Resolves WHT_RECEIVABLE GL account.
     *
     * @param companyId       owning company
     * @param branchId        originating branch (nullable)
     * @param whtTypeUid      uid of the WhtType (must be same company, active, kind=WHT_ON_RECEIPT)
     * @param partyCustomerId customer id (party_id in wht_transactions)
     * @param partyName       customer display name
     * @param partyTin        customer TIN snapshot (nullable — from PartyBase.tin)
     * @param arReceiptUid    source_ref for the certificate
     * @param taxableBase     gross taxable amount before withholding
     * @param whtAmount       withheld amount
     * @param currency        ISO-4217 currency code
     * @param certificateDate date on the certificate
     * @param journalEntryUid the GL journal entry uid to link back (may be set after capture)
     * @param actorId         performing user id
     * @return WhtCaptureResultDto with glAccountId (WHT_RECEIVABLE), whtTransactionUid, certificateNumber
     */
    WhtCaptureResultDto captureOnReceipt(Long companyId, Long branchId,
                                          String whtTypeUid,
                                          Long partyCustomerId, String partyName,
                                          String partyTin,
                                          String arReceiptUid,
                                          BigDecimal taxableBase, BigDecimal whtAmount,
                                          String currency, LocalDate certificateDate,
                                          String journalEntryUid, Long actorId);
}
