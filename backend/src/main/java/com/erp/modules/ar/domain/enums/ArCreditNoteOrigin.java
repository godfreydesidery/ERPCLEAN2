package com.erp.modules.ar.domain.enums;

/**
 * Why a credit note was raised (ADR-0014 D-2d; ADR-0021 D-11 adds RETURN).
 *
 * <ul>
 *   <li>STANDALONE — manually raised against an AR open item or unapplied; posts full GL legs
 *       (DR Revenue + DR VAT / CR AR).</li>
 *   <li>SALE_VOID — raised by the SALE.VOIDED handler; GL already reversed by the void path,
 *       so no duplicate GL post.</li>
 *   <li>RETURN — raised by SalesReturnService on a delivery return; posts full GL legs exactly
 *       as STANDALONE (DR Revenue + DR VAT / CR AR). The COGS-reversal GL is posted separately
 *       by the DeliveryReturnStockHandler.</li>
 * </ul>
 */
public enum ArCreditNoteOrigin {
    STANDALONE,
    SALE_VOID,
    /** Raised by a sales return / RMA — reverses revenue/AR/VAT for the returned goods (ADR-0021 D-11). */
    RETURN
}
