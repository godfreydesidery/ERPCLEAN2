package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.SalesOrderLineDto;

/**
 * Drop-ship fulfilment seam (ADR-0029 D-3).
 * Marks an SO line as DROP_SHIP and raises the purchase order at the named supplier.
 */
public interface DropshipService {

    /**
     * Flag an SO line as DROP_SHIP and auto-raise a PO to the designated supplier.
     *
     * @param orderUid       the parent SalesOrder uid
     * @param lineUid        the SalesOrderLine uid to flag
     * @param supplierUid    the supplier uid to whom the PO will be raised
     * @return the updated line DTO (with fulfilmentMode=DROP_SHIP, dropshipPoUid set)
     */
    SalesOrderLineDto flagDropShip(String orderUid, String lineUid, String supplierUid);

    /**
     * Called when the drop-ship PO's GR is receipted — publishes DROPSHIP.FULFILLED outbox event
     * so the GL handler posts COGS at supplier cost.
     *
     * @param salesOrderLineUid  the SO line that was drop-shipped
     * @param purchaseOrderUid   the PO that was receipted
     * @param qtyBase            quantity received (in base unit)
     * @param supplierUnitCost   actual supplier unit cost from the GR
     * @param currency           cost currency
     */
    void recordFulfilled(String salesOrderLineUid, String purchaseOrderUid,
                         java.math.BigDecimal qtyBase, java.math.BigDecimal supplierUnitCost,
                         String currency);
}
