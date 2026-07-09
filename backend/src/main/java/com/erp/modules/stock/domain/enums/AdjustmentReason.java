package com.erp.modules.stock.domain.enums;

/**
 * Fixed reason codes for manual ADJUSTMENT movements (ADR-0010 D-7, BR-STOCK-05).
 *
 * <p>The DB CHECK on {@code stock_movements.reason_code} enforces only
 * <em>presence-when-ADJUSTMENT</em>; this enum is the service-layer gate that validates the value
 * is within the accepted list. Add a new constant here when the owner expands the list; no schema
 * change required (D-7 deliberately avoids a DB enum-list CHECK for owner-tunability).
 */
public enum AdjustmentReason {

    /** Physical stocktake count differs from the system level. */
    COUNT_CORRECTION,

    /** Stock written off as physically damaged. */
    DAMAGE,

    /** Loss due to theft, spillage, or natural shrinkage. */
    SHRINKAGE,

    /** Stock past its expiry date; no batch/expiry tracking in v1. */
    EXPIRY,

    /** Manual fix to a mis-received quantity (alternative to a Goods Receipt reversal). */
    RECEIPT_CORRECTION,

    /**
     * Entering initial on-hand stock for a product whose opening-balance window has closed (its
     * first movement already posted) — the honest reason when Opening Balance is no longer allowed.
     */
    OPENING_STOCK,

    /** Any other reason — the free-text {@code note} should accompany this choice. */
    OTHER;
}
