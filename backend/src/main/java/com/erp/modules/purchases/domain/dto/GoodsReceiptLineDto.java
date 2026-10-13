package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.GoodsReceiptLine;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Read-only response DTO for a single GR line (ADR-0011 D-12).
 * V76: adds lotNumber, manufactureDate, expiryDate, serialNumbers.
 */
public record GoodsReceiptLineDto(
        Long   id,
        String uid,
        Long   goodsReceiptId,
        Long   purchaseOrderLineId,
        short  lineNo,
        Long   productId,
        String productCode,
        String productName,
        Long   unitId,
        String unitName,
        BigDecimal receivedQty,
        BigDecimal qtyInBase,
        BigDecimal unitCostAmount,
        BigDecimal lineCostAmount,
        String     currency,
        // V76 — lot/batch + serial tracking (null/empty when not captured)
        String       lotNumber,
        LocalDate    manufactureDate,
        LocalDate    expiryDate,
        List<String> serialNumbers,
        // PUR-02 (additive): base quantity already sent back to the supplier on confirmed returns,
        // so a return screen can show what is still returnable in the line's own unit.
        BigDecimal   returnedQtyInBase
) {
    /** Pre-PUR-02 shape (no returned qty) — kept so existing callers compile unchanged. */
    public GoodsReceiptLineDto(Long id, String uid, Long goodsReceiptId, Long purchaseOrderLineId,
                               short lineNo, Long productId, String productCode, String productName,
                               Long unitId, String unitName, BigDecimal receivedQty,
                               BigDecimal qtyInBase, BigDecimal unitCostAmount,
                               BigDecimal lineCostAmount, String currency, String lotNumber,
                               LocalDate manufactureDate, LocalDate expiryDate,
                               List<String> serialNumbers) {
        this(id, uid, goodsReceiptId, purchaseOrderLineId, lineNo, productId, productCode,
                productName, unitId, unitName, receivedQty, qtyInBase, unitCostAmount,
                lineCostAmount, currency, lotNumber, manufactureDate, expiryDate, serialNumbers,
                BigDecimal.ZERO);
    }

    /** Construct from entity + pre-fetched serial numbers list. */
    public static GoodsReceiptLineDto from(GoodsReceiptLine l, List<String> serials) {
        return new GoodsReceiptLineDto(
                l.getId(), l.getUid(),
                l.getGoodsReceipt().getId(),
                l.getPurchaseOrderLineId(),
                l.getLineNo(),
                l.getProductId(), l.getProductCode(), l.getProductName(),
                l.getUnitId(), l.getUnitName(),
                l.getReceivedQty(), l.getQtyInBase(),
                l.getUnitCostAmount(), l.getLineCostAmount(),
                CurrencyCode.value(l.getCurrency()),
                l.getLotNumber(), l.getManufactureDate(), l.getExpiryDate(),
                serials != null ? serials : List.of(),
                l.getReturnedQtyInBase() != null ? l.getReturnedQtyInBase() : BigDecimal.ZERO);
    }

    /**
     * Back-compat factory when serials are not loaded (read paths that don't join serials).
     * serialNumbers will be an empty list.
     */
    public static GoodsReceiptLineDto from(GoodsReceiptLine l) {
        return from(l, List.of());
    }
}
