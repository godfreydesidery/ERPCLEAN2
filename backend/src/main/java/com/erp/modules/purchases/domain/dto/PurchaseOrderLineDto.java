package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.PurchaseOrderLine;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Read-only response DTO for a single PO line (ADR-0011 D-12).
 *
 * <p>Includes derived {@code outstandingQtyInBase} and {@code fullyReceived} (not stored —
 * computed in the service/DTO from maintained columns, ADR-0011 D-12).
 */
public record PurchaseOrderLineDto(
        Long   id,
        String uid,
        Long   purchaseOrderId,
        short  lineNo,
        Long   productId,
        String productCode,
        String productName,
        Long   unitId,
        String unitName,
        BigDecimal orderedQty,
        BigDecimal orderedQtyInBase,
        BigDecimal receivedQtyInBase,
        BigDecimal billedQtyInBase,
        BigDecimal cancelledQty,
        LocalDate  requiredByDate,
        BigDecimal outstandingQtyInBase,  // derived: ordered − received
        boolean    fullyReceived,
        BigDecimal unitCostAmount,
        BigDecimal lineTotalAmount,
        String     currency,
        // ADR-0041 D4: per-line dimension tags (soft-FK → dimension_values.id; nullable).
        // Exposed so AP can copy them onto a derived supplier_bill_line via the service boundary
        // (never importing a Purchases entity — NFR-AP-06).
        Long       costCentreValueId,
        Long       departmentValueId
) {
    public static PurchaseOrderLineDto from(PurchaseOrderLine l) {
        BigDecimal outstanding = l.getOrderedQtyInBase().subtract(l.getReceivedQtyInBase());
        boolean fully = outstanding.compareTo(BigDecimal.ZERO) <= 0;
        return new PurchaseOrderLineDto(
                l.getId(), l.getUid(),
                l.getPurchaseOrder().getId(),
                l.getLineNo(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getOrderedQty(), l.getOrderedQtyInBase(), l.getReceivedQtyInBase(),
                l.getBilledQtyInBase(), l.getCancelledQty(), l.getRequiredByDate(),
                outstanding, fully,
                l.getUnitCostAmount(), l.getLineTotalAmount(),
                CurrencyCode.value(l.getCurrency()),
                l.getCostCentreValueId(), l.getDepartmentValueId());
    }
}
