package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.DeliveryLine;
import java.math.BigDecimal;

/**
 * One delivery line.
 *
 * @param qtyDelivered     quantity in the line's unit ({@code unitName} — the sales-order line's
 *                         unit, e.g. Crate)
 * @param qtyDeliveredBase the same quantity in the product's base unit; {@code qtyInvoicedBase}
 *                         and {@code returnedQtyBase} are base quantities too
 * @param factorToBase     base units in ONE {@code unitName} (1 for a base-unit line), taken from
 *                         the sales-order line; null only when it could not be resolved. Divide a
 *                         {@code *Base} quantity by it to show it in the line's unit.
 */
public record DeliveryLineDto(
        Long id,
        String uid,
        short lineNo,
        Long salesOrderLineId,
        String salesOrderLineUid,
        Long productId,
        String productCode,
        String productName,
        Long unitId,
        String unitName,
        BigDecimal qtyDelivered,
        BigDecimal qtyDeliveredBase,
        BigDecimal qtyInvoicedBase,
        BigDecimal returnedQtyBase,
        BigDecimal issueValueAmount,
        String currency,
        BigDecimal factorToBase
) {
    /** The stored values as they are, without a sales-order line to derive the factor from. */
    public static DeliveryLineDto from(DeliveryLine l) {
        return from(l, null, null);
    }

    /**
     * @param qtyDeliveredInUnit {@code qtyDeliveredBase} expressed in the line's unit, derived from
     *                           the sales-order line — identical to the stored value for every
     *                           delivery recorded under the line-unit contract, and the correct
     *                           figure for one recorded before it (SAL-01: those stored the BASE
     *                           count against the pack unit, so a crate of 25 printed as
     *                           "25 Crate"). Null keeps the stored value.
     * @param factorToBase       base units per one line unit, from the sales-order line (nullable)
     */
    public static DeliveryLineDto from(DeliveryLine l, BigDecimal qtyDeliveredInUnit,
                                       BigDecimal factorToBase) {
        BigDecimal qtyDelivered = qtyDeliveredInUnit != null
                ? qtyDeliveredInUnit : l.getQtyDelivered();
        return new DeliveryLineDto(
                l.getId(), l.getUid(), l.getLineNo(),
                l.getSalesOrderLineId(), l.getSalesOrderLineUid(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                qtyDelivered, l.getQtyDeliveredBase(),
                l.getQtyInvoicedBase(), l.getReturnedQtyBase(),
                l.getIssueValueAmount(),
                l.getCurrency().value(),
                factorToBase);
    }
}
