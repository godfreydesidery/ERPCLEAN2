package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/** Request to raise a standalone credit note (FR-AR-14, OQ-AR-04). */
public record RaiseCreditNoteRequest(
        String companyUid,
        String customerUid,
        /** The AR open item uid being reduced; null for an unapplied credit. */
        String arInvoiceUid,
        LocalDate noteDate,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        String currency,
        String reason
) {}
