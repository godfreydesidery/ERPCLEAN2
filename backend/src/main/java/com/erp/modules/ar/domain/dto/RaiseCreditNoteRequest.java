package com.erp.modules.ar.domain.dto;

import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Request to raise a credit note (FR-AR-14, OQ-AR-04; ADR-0021 D-11 adds origin).
 *
 * <p>The {@code origin} field defaults to {@link ArCreditNoteOrigin#STANDALONE} so that existing
 * callers (ArController, SALE_VOID handler) are unaffected — they pass no origin and still get
 * the standalone GL-posting path.
 */
public record RaiseCreditNoteRequest(
        String companyUid,
        String customerUid,
        /** The AR open item uid being reduced; null for an unapplied credit. */
        String arInvoiceUid,
        LocalDate noteDate,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        String currency,
        String reason,
        /**
         * Why the credit note is being raised (ADR-0021 D-11).
         * Defaults to STANDALONE — existing callers that omit this field are unaffected.
         */
        ArCreditNoteOrigin origin
) {
    /** Compact canonical form — existing callers build this constructor without origin. */
    public RaiseCreditNoteRequest(String companyUid, String customerUid, String arInvoiceUid,
                                   LocalDate noteDate, BigDecimal netAmount, BigDecimal vatAmount,
                                   String currency, String reason) {
        this(companyUid, customerUid, arInvoiceUid, noteDate, netAmount, vatAmount, currency,
                reason, ArCreditNoteOrigin.STANDALONE);
    }
}
