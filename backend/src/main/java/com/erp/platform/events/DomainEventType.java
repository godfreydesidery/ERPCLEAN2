package com.erp.platform.events;

/**
 * Canonical event-type and aggregate-type string constants for the transactional outbox
 * (ADR-0009 D-3). Both producers and consumers reference the same constant so a typo cannot
 * silently misroute an event.
 *
 * <p>New event types are added here under their owning module's ADR. Do not scatter string literals
 * in producer / consumer code.
 */
public final class DomainEventType {

    // ---------------------------------------------------------------------------
    // Event types (MODULE.EVENT form)
    // ---------------------------------------------------------------------------

    /** Sales invoice finalised — stock deduction trigger (ADR-0008 D-9, ADR-0009 D-3). */
    public static final String SALE_FINALISED         = "SALE.FINALISED";

    /** Sales invoice voided — compensating reversal trigger (ADR-0008 D-9, ADR-0009 D-3). */
    public static final String SALE_VOIDED            = "SALE.VOIDED";

    /** Goods receipt received — stock receipt trigger (ADR-0009 D-3; built by ADR-0011). */
    public static final String STOCK_RECEIVED         = "STOCK.RECEIVED";

    /** Goods receipt voided — compensating receipt reversal (ADR-0009 D-3; built by ADR-0011). */
    public static final String STOCK_RECEIPT_VOIDED   = "STOCK.RECEIPT.VOIDED";

    /** Delivery confirmed — stock-issue + COGS trigger for SO-sourced sales (ADR-0021 D-6). */
    public static final String DELIVERY_CONFIRMED     = "DELIVERY.CONFIRMED";

    /** Delivery returned — COGS reversal + stock-in trigger for returns (ADR-0021 D-11, Stage 2). */
    public static final String DELIVERY_RETURNED      = "DELIVERY.RETURNED";

    // --- approvals (ADR-0022) ---

    /** Approval request submitted — policy matched, PENDING chain created (ADR-0022 D-10). */
    public static final String APPROVAL_SUBMITTED     = "APPROVAL.SUBMITTED";

    /** Approval request reached a terminal state (APPROVED/REJECTED/RECALLED/CANCELLED) (ADR-0022 D-10). */
    public static final String APPROVAL_RESOLVED      = "APPROVAL.RESOLVED";

    // ---------------------------------------------------------------------------
    // Aggregate types (the producing aggregate kind — used for diagnostics/replay)
    // ---------------------------------------------------------------------------

    public static final String AGG_SALES_INVOICE  = "SALES_INVOICE";
    public static final String AGG_GOODS_RECEIPT  = "GOODS_RECEIPT";
    public static final String AGG_DELIVERY       = "DELIVERY";
    public static final String AGG_SALES_RETURN   = "SALES_RETURN";

    // --- approvals (ADR-0022) ---
    public static final String AGG_APPROVAL_REQUEST = "APPROVAL_REQUEST";

    // --- documents (ADR-0023) ---
    /** A document PDF was rendered and the record committed (ADR-0023 D-7). */
    public static final String DOCUMENT_GENERATED     = "DOCUMENT.GENERATED";
    public static final String AGG_GENERATED_DOCUMENT = "GENERATED_DOCUMENT";

    // --- inventory-depth (ADR-0028) ---

    /** Stock transfer dispatched — TRANSFER_OUT at source + in-transit holding (ADR-0028 D-12). */
    public static final String STOCK_TRANSFER_DISPATCHED = "STOCK.TRANSFER.DISPATCHED";

    /** Stock transfer received — TRANSFER_IN from in-transit to dest (ADR-0028 D-12). */
    public static final String STOCK_TRANSFER_RECEIVED   = "STOCK.TRANSFER.RECEIVED";

    /** Stock count posted — informational (GL posts synchronously, not via this event) (ADR-0028 D-12). */
    public static final String STOCK_COUNT_POSTED        = "STOCK.COUNT.POSTED";

    public static final String AGG_STOCK_TRANSFER        = "STOCK_TRANSFER";
    public static final String AGG_STOCK_COUNT           = "STOCK_COUNT";
    // --- fixed-assets (ADR-0030) ---
    /** A depreciation run completed and the GL journal was posted (ADR-0030 D-8). */
    public static final String DEPRECIATION_RUN_EXECUTED = "DEPRECIATION.RUN.EXECUTED";
    public static final String AGG_DEPRECIATION_RUN      = "DEPRECIATION_RUN";

    // --- procurement-depth (ADR-0027) ---

    /** Landed cost confirmed — capitalise allocated amounts into inventory value (ADR-0027 D-5). */
    public static final String LANDED_COST_ALLOCATED = "LANDED_COST.ALLOCATED";

    /** Purchase return confirmed — stock-out at original receipt cost + AP debit note (ADR-0027 D-7). */
    public static final String PURCHASE_RETURNED      = "PURCHASE.RETURNED";

    public static final String AGG_LANDED_COST    = "LANDED_COST";
    public static final String AGG_PURCHASE_RETURN = "PURCHASE_RETURN";

    // --- sales-depth (ADR-0029) ---

    /** Drop-ship SO line fulfilled by supplier — triggers COGS at supplier cost (ADR-0029 D-11). */
    public static final String DROPSHIP_FULFILLED       = "DROPSHIP.FULFILLED";

    /** Standing order generation run produced a child SO (ADR-0029 D-14). */
    public static final String STANDING_ORDER_GENERATED = "STANDING_ORDER.GENERATED";

    public static final String AGG_SALES_ORDER    = "SALES_ORDER";
    public static final String AGG_STANDING_ORDER = "STANDING_ORDER";

    // --- hr-payroll (ADR-0032) ---

    /** Payroll run finalised/posted — GL posting + Cash disbursement trigger (ADR-0032 D-11). */
    public static final String PAYROLL_FINALISED  = "PAYROLL.FINALISED";

    /** Payroll run reversed — GL reversal trigger (ADR-0032 D-11). */
    public static final String PAYROLL_REVERSED   = "PAYROLL.REVERSED";

    public static final String AGG_PAYROLL_RUN    = "PAYROLL_RUN";

    // --- notifications (ADR-0024) ---
    /** AR receipt recorded and allocated — payment notification trigger (ADR-0024 D-8). */
    public static final String PAYMENT_RECEIVED       = "PAYMENT.RECEIVED";
    public static final String AGG_AR_RECEIPT         = "AR_RECEIPT";

    // --- projects (ADR-0033) ---

    /** Materials issued to a project — triggers cost recording on the project dimension (ADR-0033 D-5). */
    public static final String PROJECT_MATERIAL_ISSUED = "PROJECT.MATERIAL.ISSUED";
    public static final String AGG_PROJECT_ISSUE       = "PROJECT_ISSUE";

    // --- budgeting (ADR-0034) ---
    /**
     * Reserved for future deferred enforcement/commitment round (ADR-0034 D-9).
     * Declared here to claim the namespace; NOT published in v1.
     */
    public static final String BUDGET_VERSION_APPROVED = "BUDGET.VERSION.APPROVED";
    public static final String AGG_BUDGET_VERSION       = "BUDGET_VERSION";

    private DomainEventType() {
    }
}
