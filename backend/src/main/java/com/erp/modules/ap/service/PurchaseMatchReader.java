package com.erp.modules.ap.service;

import com.erp.modules.purchases.domain.dto.GoodsReceiptLineDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderApprovalSnapshotDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.service.GoodsReceiptService;
import com.erp.modules.purchases.service.PurchaseOrderService;
import java.util.Collection;
import java.util.Map;
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
     * Fetch a PO header DTO by uid. Returns empty if not found or on any lookup error.
     * Used by SupplierBillServiceImpl to assert supplier ownership before bill entry.
     *
     * <p><b>Not a pure read.</b> Purchases reconciles the order's approval status against the
     * approval engine on this path and persists the result, so the caller sees a decision taken in
     * the Approvals inbox. That is what a control point wants and what a listing must not do — call
     * it only from a read-WRITE transaction, and use {@link #findApprovalSnapshots(Collection)}
     * when all you need is to display where an order stands.
     */
    public Optional<PurchaseOrderDto> findPo(String purchaseOrderUid) {
        if (purchaseOrderUid == null || purchaseOrderUid.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(purchaseOrders.getByUid(purchaseOrderUid));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * Origin + stored approval status for a whole set of orders in ONE call, keyed by uid.
     *
     * <p>The read-safe counterpart to {@link #findPo(String)}: no approval-engine poll, no mutation
     * of the order, no audit row — so a bill list can use it from a {@code readOnly} transaction
     * without the N+1 and without a write that Hibernate would silently discard anyway.
     *
     * <p>Fail-open, like every method here: a uid the caller may not see, one that does not exist,
     * or a lookup that blows up altogether is simply absent from the map. A listing must render
     * even when this seam has a bad day.
     */
    public Map<String, PurchaseOrderApprovalSnapshotDto> findApprovalSnapshots(
            Collection<String> purchaseOrderUids) {
        if (purchaseOrderUids == null || purchaseOrderUids.isEmpty()) {
            return Map.of();
        }
        try {
            return purchaseOrders.findApprovalSnapshots(purchaseOrderUids);
        } catch (Exception e) {
            return Map.of();
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
