package com.erp.modules.ap.service;

import com.erp.modules.purchases.domain.dto.GoodsReceiptLineDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.service.GoodsReceiptService;
import com.erp.modules.purchases.service.PurchaseOrderService;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads PO + GR line match facts for the 3-way match (ADR-0015 D-10/D-11).
 * Accesses Purchases only via service interfaces + DTOs — never imports a Purchases entity
 * (ModuleBoundaryTest; NFR-AP-06).
 */
@Component
@Transactional(readOnly = true)
public class PurchaseMatchReader {

    private final PurchaseOrderService purchaseOrders;
    private final GoodsReceiptService  goodsReceipts;

    public PurchaseMatchReader(PurchaseOrderService purchaseOrders,
                                GoodsReceiptService goodsReceipts) {
        this.purchaseOrders = purchaseOrders;
        this.goodsReceipts  = goodsReceipts;
    }

    /**
     * Fetch a PO line DTO by uid, scoped to the given PO uid.
     * Returns empty if not found.
     */
    public Optional<PurchaseOrderLineDto> findPoLine(String purchaseOrderUid, String poLineUid) {
        try {
            return purchaseOrders.listLines(purchaseOrderUid).stream()
                    .filter(l -> poLineUid.equals(l.uid()))
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Fetch a GR line DTO by uid, from the GR identified by goodsReceiptUid.
     * Lines are embedded in the GoodsReceiptDto (ADR-0011 D-12).
     */
    public Optional<GoodsReceiptLineDto> findGrLine(String goodsReceiptUid, String grLineUid) {
        try {
            return goodsReceipts.getByUid(goodsReceiptUid).lines().stream()
                    .filter(l -> grLineUid.equals(l.uid()))
                    .findFirst();
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
