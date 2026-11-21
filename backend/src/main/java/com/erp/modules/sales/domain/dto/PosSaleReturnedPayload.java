package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * Typed payload of the {@code POS.SALE.RETURNED} outbox event (ADR-0072), written in the same
 * transaction as the return and the cash refund.
 *
 * <p>Consumers: stock (puts the returned stockable goods back at their ORIGINAL issue cost, Dr
 * Inventory / Cr COGS), GL (Dr Sales revenue, Dr VAT payable, Cr Cash as a {@code SALES_REVERSAL}
 * journal whose {@code source_ref} is {@code returnUid}) and the cash book (mirrors that journal's
 * cash leg as a {@code SALE_REFUND}). Amounts are in the invoice currency; the GL converts to base.
 */
public record PosSaleReturnedPayload(
        String returnUid,
        String returnNumber,
        String invoiceUid,
        String invoiceNumber,
        Long companyId,
        Long branchId,
        String currency,
        Instant returnedAt,
        BigDecimal netAmount,
        BigDecimal vatAmount,
        BigDecimal grossAmount,
        List<LineItem> lines
) {

    /**
     * @param productId       the SOLD line's product (a kit/recipe is exploded by stock, not here)
     * @param productUid      its uid, for the recipe resolver
     * @param qtyReturnedBase the returned quantity in the product's base unit
     */
    public record LineItem(
            Long productId,
            String productUid,
            BigDecimal qtyReturnedBase
    ) {
    }
}
