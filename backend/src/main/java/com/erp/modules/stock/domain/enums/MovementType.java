package com.erp.modules.stock.domain.enums;

/**
 * The six v1 movement types admitted by {@code chk_stock_movement_type} (ADR-0010 D-4/D-6).
 *
 * <p>TRANSFER_OUT / TRANSFER_IN are reserved in the enum vocabulary (so the upgrade is additive —
 * OQ-STOCK-08) but are deliberately excluded from the DB CHECK until the feature is built.
 */
public enum MovementType {

    /** Goods received into stock from a Goods Receipt (Purchases event). + sign. */
    GOODS_RECEIPT,

    /** Stock deducted on sale finalisation (Sales event). − sign. */
    SALE_ISSUE,

    /** Reversal of a prior SALE_ISSUE when an invoice is voided (Sales void event). + sign. */
    SALE_REVERSAL,

    /** Reversal of a prior GOODS_RECEIPT when a Goods Receipt is voided (Purchases void event). − sign. */
    GOODS_RECEIPT_REVERSAL,

    /** Manual signed adjustment with a mandatory AdjustmentReason (D-7). ± sign. */
    ADJUSTMENT,

    /** Manual seed of an initial on-hand level for a never-tracked product at a branch. + sign. */
    OPENING_BALANCE,

    // Reserved — excluded from DB CHECK in v1 (OQ-STOCK-08). Do NOT store these values yet.
    TRANSFER_OUT,
    TRANSFER_IN,

    // --- procurement-depth (ADR-0027 D-7, admitted by V36 chk_stock_movement_type widen) ---
    /** Goods returned to supplier from a Purchase Return confirm. − sign. */
    PURCHASE_RETURN,

    /** Material issued directly to a project task (ADR-0033 D-5). − sign on stock-on-hand. */
    ISSUE_TO_PROJECT,

    // --- Manufacturing / Production increment (ADR-0035 D-3, admitted by V74 widen) ---
    /** Component material issued from stock to work-in-progress. − sign. */
    PRODUCTION_ISSUE,

    /** Finished goods received from a completed work order. + sign. */
    PRODUCTION_RECEIPT,

    /** Reversal of a PRODUCTION_ISSUE (cancel path). + sign. */
    PRODUCTION_ISSUE_REVERSAL,

    /** Reversal of a PRODUCTION_RECEIPT (cancel path). − sign. */
    PRODUCTION_RECEIPT_REVERSAL;
}
