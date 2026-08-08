package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.DirectGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;

/**
 * Receive goods into stock with no prior LPO (K3, Kilimanjaro 2026-08-08).
 *
 * <p>The approved design does NOT make {@code goods_receipts.purchase_order_id} nullable: the
 * service auto-raises a purchase order for the delivery and receives against it in one transaction.
 * Everything downstream — the AP three-way match, GRNI clearing, purchase returns, outstanding
 * tracking, PO status — is untouched and needs no direct-receipt special case.
 *
 * <p>The order it raises is stamped
 * {@link com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin#DIRECT_RECEIPT} (V96), which is
 * what keeps it out of the buyer's purchase-order list while leaving it fully auditable.
 */
public interface DirectGoodsReceiptService {

    /**
     * Auto-raise a purchase order for the supplied lines, place it, and receive it in full — all in
     * ONE transaction, so a failure anywhere leaves neither a phantom PO nor a partial receipt.
     *
     * <p>Emits the same {@code STOCK.RECEIVED} outbox event as any other receipt, so inventory
     * valuation and the DR INVENTORY / CR GRNI posting behave identically.
     *
     * @return the finalised receipt (GRN-####), with the auto-raised PO's uid on it
     */
    GoodsReceiptDto receiveDirect(DirectGoodsReceiptRequest req);
}
