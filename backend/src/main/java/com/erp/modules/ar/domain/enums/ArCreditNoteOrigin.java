package com.erp.modules.ar.domain.enums;

/** Whether a credit note is standalone (posts to GL) or rides a voided sale (no GL post). */
public enum ArCreditNoteOrigin {
    STANDALONE,
    SALE_VOID
}
