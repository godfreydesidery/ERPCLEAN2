package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.ApprovePoRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.VoidPurchaseOrderRequest;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Purchase Order lifecycle service (ADR-0011, FR-PURCH-01a/02a/04/05/06).
 */
public interface PurchaseOrderService {

    /** Create a DRAFT PO (supplier + optional initial lines). */
    PurchaseOrderDto create(CreatePurchaseOrderRequest req);

    /** Get a PO by uid; assertCanActIn on every read path. */
    PurchaseOrderDto getByUid(String uid);

    /** Paged list for a company (scoped by tenant predicate). */
    Page<PurchaseOrderDto> list(Long companyId, String q, Pageable pageable);

    /** Update header fields (supplier, notes, expected date) while DRAFT. */
    PurchaseOrderDto update(String uid, UpdatePurchaseOrderRequest req);

    /** Add a line to a DRAFT PO. */
    PurchaseOrderLineDto addLine(String purchaseOrderUid, AddPurchaseOrderLineRequest req);

    /** Update an existing line on a DRAFT PO. */
    PurchaseOrderLineDto updateLine(String purchaseOrderUid, String lineUid,
                                    UpdatePurchaseOrderLineRequest req);

    /** Remove a line from a DRAFT PO. */
    void removeLine(String purchaseOrderUid, String lineUid);

    /** List all lines for a PO. */
    List<PurchaseOrderLineDto> listLines(String purchaseOrderUid);

    /** Transition DRAFT → ORDERED; assign PO-####; freeze lines. */
    PurchaseOrderDto placeOrder(String uid);

    /** Transition {ORDERED, PARTIALLY_RECEIVED, RECEIVED} → CLOSED. */
    PurchaseOrderDto closeOrder(String uid);

    /** Transition {DRAFT, ORDERED, PARTIALLY_RECEIVED} → VOID. */
    PurchaseOrderDto voidOrder(String uid, VoidPurchaseOrderRequest req);

    // --- procurement-depth approval seam (ADR-0027 D-6) ---

    /**
     * Permission-gated fallback approve: sets approval_status = APPROVED so placeOrder can proceed.
     * Required permission: {@code PURCHASE.ORDER.APPROVE}.
     */
    PurchaseOrderDto approvePo(String uid, ApprovePoRequest req);

    /**
     * Permission-gated fallback reject: sets approval_status = REJECTED.
     * Required permission: {@code PURCHASE.ORDER.REJECT}.
     */
    PurchaseOrderDto rejectPo(String uid, ApprovePoRequest req);

    /**
     * Create a PO from an awarded supplier quote (ADR-0027 D-4 award flow).
     * Copies lines at quoted prices; sets source_quote_uid.
     */
    PurchaseOrderDto createFromQuote(String quoteUid);

    /**
     * APPROVALS-047: Submit a DRAFT PO to the approval engine (ADR-0027 D-6).
     * Calls {@code PoApprovalGate.submit()} in the same TX; sets approval_status = PENDING
     * (or APPROVED when the engine auto-approves per policy).  The PO remains DRAFT — it is
     * placed (DRAFT→ORDERED) only once approval_status = APPROVED via {@link #placeOrder}.
     */
    PurchaseOrderDto submitForApproval(String uid);
}
