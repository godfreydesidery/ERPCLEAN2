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

    // ---------------------------------------------------------------------------
    // Aggregate types (the producing aggregate kind — used for diagnostics/replay)
    // ---------------------------------------------------------------------------

    public static final String AGG_SALES_INVOICE  = "SALES_INVOICE";
    public static final String AGG_GOODS_RECEIPT  = "GOODS_RECEIPT";
    public static final String AGG_DELIVERY       = "DELIVERY";
    public static final String AGG_SALES_RETURN   = "SALES_RETURN";

    // --- documents (ADR-0023) ---

    /** A document PDF was rendered and the record committed (ADR-0023 D-7). */
    public static final String DOCUMENT_GENERATED     = "DOCUMENT.GENERATED";

    public static final String AGG_GENERATED_DOCUMENT = "GENERATED_DOCUMENT";

    private DomainEventType() {
    }
}
