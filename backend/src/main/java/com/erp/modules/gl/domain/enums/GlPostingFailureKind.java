package com.erp.modules.gl.domain.enums;

/**
 * Which automatic poster a swallowed GL posting failure came from (ACC-02). The kind decides how a
 * "Re-post" re-invokes that same poster: each kind is owned by exactly one
 * {@code GlPostingRetryHandler} (GL owns the sales/draft/reversal kinds, Stock owns the inventory
 * kinds).
 */
public enum GlPostingFailureKind {
    /** GLPostingSafeInvoker#postSaleInNewTx — DR Cash/AR, CR Revenue, CR VAT. */
    SALE,
    /** GLPostingSafeInvoker#postInNewTx — a fully built draft from any module. */
    JOURNAL_DRAFT,
    /** GLPostingSafeInvoker#postReversalInNewTx — reversal of a known journal entry. */
    REVERSAL,
    /** SaleVoidingHandler — the void arrived but the sale itself had never reached the GL. */
    SALE_VOID,
    /** InventoryGlPoster#postReceiptInNewTx — DR Inventory, CR GRNI. */
    STOCK_RECEIPT,
    /** InventoryGlPoster#postCogsInNewTx — DR COGS, CR Inventory. */
    SALE_COGS,
    /** InventoryGlPoster#postCogsForProjectInNewTx — project-tagged COGS. */
    PROJECT_COGS,
    /** InventoryGlPoster#postReceiptReversalInNewTx — DR GRNI, CR Inventory. */
    STOCK_RECEIPT_REVERSAL,
    /** InventoryGlPoster#postSaleReversalInNewTx — DR Inventory, CR COGS. */
    SALE_COGS_REVERSAL,
    /** InventoryGlPoster#postLandedCostInNewTx. */
    LANDED_COST,
    /** InventoryGlPoster#postPurchaseReturnInNewTx. */
    PURCHASE_RETURN
}
