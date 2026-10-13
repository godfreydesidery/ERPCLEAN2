package com.erp.modules.tax.domain.dto;

import com.erp.modules.tax.domain.enums.WhtKind;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One row of the WHT period register (ADR-0017 D-9).
 */
public record WhtRegisterRowDto(
        String whtNumber,
        WhtKind kind,
        String partyKind,
        String partyName,
        String sourceRef,
        BigDecimal taxableBase,
        BigDecimal whtAmount,
        LocalDate certificateDate,
        /** ACC-07: the certificate's uid — the handle for the per-row remit action. */
        String uid,
        /** ACC-07: whether it has been remitted to TRA. */
        boolean remitted
) {
    /** The original eight-field shape (no uid / remitted flag). */
    public WhtRegisterRowDto(String whtNumber, WhtKind kind, String partyKind, String partyName,
                             String sourceRef, BigDecimal taxableBase, BigDecimal whtAmount,
                             LocalDate certificateDate) {
        this(whtNumber, kind, partyKind, partyName, sourceRef, taxableBase, whtAmount,
                certificateDate, null, false);
    }
}
