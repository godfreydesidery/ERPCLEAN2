package com.erp.modules.purchases.domain.enums;

/**
 * Purchase-requisition priority/urgency (P2 mechanical, GAP-FIX-WORKLIST purchases/PurchaseRequisition).
 * Stored on purchase_requisitions.priority; nullable.
 */
public enum RequisitionPriority {
    LOW,
    NORMAL,
    HIGH,
    URGENT
}
