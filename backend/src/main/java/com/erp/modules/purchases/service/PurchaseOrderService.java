package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.ApprovePoRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderApprovalSnapshotDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.VoidPurchaseOrderRequest;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Purchase Order lifecycle service (ADR-0011, FR-PURCH-01a/02a/04/05/06).
 */
public interface PurchaseOrderService {

    /** Create a DRAFT PO (supplier + optional initial lines). Provenance is MANUAL. */
    PurchaseOrderDto create(CreatePurchaseOrderRequest req);

    /**
     * Create a DRAFT PO stamped with an explicit provenance (V96, K3).
     *
     * <p>Purchases-internal: the only non-MANUAL caller is {@code DirectGoodsReceiptService}, which
     * synthesises the order that anchors a receipt with no prior LPO. Every controller-reachable
     * path goes through {@link #create(CreatePurchaseOrderRequest)} instead, so provenance can never
     * be chosen by a client — {@code origin} is deliberately absent from
     * {@link CreatePurchaseOrderRequest}.
     */
    PurchaseOrderDto createWithOrigin(CreatePurchaseOrderRequest req, PurchaseOrderOrigin origin);

    /**
     * Get a PO by uid; assertCanActIn on every read path.
     *
     * <p>NOT a pure read: it reconciles {@code approval_status} against the approval engine and
     * persists the answer (plus an audit row), so a decision taken in the generic Approvals inbox
     * lands on the order. Call it from a read-WRITE transaction — from a {@code readOnly} one
     * Hibernate's MANUAL flush mode drops the reconcile silently. A caller that only needs to
     * display where an order stands should use {@link #findApprovalSnapshots(Collection)} instead.
     */
    PurchaseOrderDto getByUid(String uid);

    /**
     * Origin + stored approval status for a set of orders, keyed by uid — one query, no
     * approval-engine poll, no write (K3 follow-up).
     *
     * <p>This is the read seam for screens that merely SHOW where an order stands, notably the AP
     * bill list, which needs those two facts per bill and previously reached them through
     * {@link #getByUid(String)} — one engine round-trip per row, from a {@code readOnly}
     * transaction whose mutation Hibernate then discarded. Control points that must act on a
     * freshly reconciled decision (payment release, placement) keep using {@code getByUid}.
     *
     * <p>Tenancy is scoped from the LOADED order: an order in a company the caller may not act in
     * is simply absent from the result rather than throwing, so one stray reference cannot 500 a
     * whole page. Unknown, blank and null uids are absent too; callers must treat "absent" as
     * "nothing known", never as a decision.
     */
    Map<String, PurchaseOrderApprovalSnapshotDto> findApprovalSnapshots(Collection<String> uids);

    /**
     * Paged list for a company (scoped by tenant predicate).
     *
     * <p>{@code includeDirectReceipts} false is the buyer's list: orders synthesised for a direct
     * goods receipt are excluded so they do not clutter the screen. True returns every provenance.
     */
    Page<PurchaseOrderDto> list(Long companyId, String q, boolean includeDirectReceipts,
                                Pageable pageable);

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
     *
     * <p>Only an order actually awaiting a decision can be approved. Stamping APPROVED on an order
     * that was never submitted records a review that did not happen, so it is refused.
     */
    PurchaseOrderDto approvePo(String uid, ApprovePoRequest req);

    /**
     * Permission-gated fallback reject: sets approval_status = REJECTED.
     * Required permission: {@code PURCHASE.ORDER.REJECT}.
     *
     * <p>Same state gate as {@link #approvePo}: only an order awaiting a decision can be rejected.
     * To stop an order that is not under approval, void it instead.
     */
    PurchaseOrderDto rejectPo(String uid, ApprovePoRequest req);

    /**
     * Create a PO from an awarded supplier quote (ADR-0027 D-4 award flow).
     * Copies lines at quoted prices; sets source_quote_uid.
     */
    PurchaseOrderDto createFromQuote(String quoteUid);

    /**
     * Create a PO from an APPROVED purchase requisition (D-3 — requisition Convert flow).
     * Mirrors {@link #createFromQuote}: copies the requisition's lines (requestedQty → orderedQty,
     * estimatedUnitCost → unitCost — null defaults to zero with an auto-note to satisfy the
     * zero-cost-needs-note rule) and sets {@code source_requisition_uid}. The supplier is NOT on the
     * requisition — it is supplied by the caller (Convert request) and resolved company-scoped
     * (rejects ARCHIVED, same as {@code resolveSupplier}).
     *
     * @param requisitionUid the source requisition uid; must resolve to an entity in the caller's
     *                        company scope (asserted internally)
     * @param supplierUid    the supplier to order from (required)
     * @param currency       optional currency override; defaults to the company's base currency
     * @return the created PO (DRAFT, with lines) — caller (PurchaseRequisitionServiceImpl) links the
     *         requisition's convertedToUid to {@code po.uid()} and stamps each requisition line's
     *         convertedToPoLineUid for traceability.
     */
    PurchaseOrderDto createFromRequisition(String requisitionUid, String supplierUid, String currency);

    /**
     * APPROVALS-047: Submit a DRAFT PO to the approval engine (ADR-0027 D-6).
     * Calls {@code PoApprovalGate.submit()} in the same TX; sets approval_status = PENDING
     * (or APPROVED when the engine auto-approves per policy).  The PO remains DRAFT — it is
     * placed (DRAFT→ORDERED) only once approval_status = APPROVED via {@link #placeOrder}.
     *
     * <p><b>Idempotent</b>: submitting an order that is already awaiting a decision returns it
     * unchanged rather than refusing, matching the approvals engine's own per-document contract
     * (and {@code SalesOrderServiceImpl}). It refuses only where nothing would be submitted at
     * all — the order is not a draft, its approval is already closed, or the company does not
     * require approval for it (whose two causes are reported distinctly).
     */
    PurchaseOrderDto submitForApproval(String uid);

    /**
     * Raise the post-hoc <b>ratification</b> request for a direct goods receipt (K3 owner decision,
     * 2026-08-08).
     *
     * <p>A direct receipt is exempt from PRE-approval — see
     * {@link PoApprovalGate#isExemptFromPreApproval} for why the pre-approval demand was
     * structurally unsatisfiable — so the spend is reviewed after the fact instead. This submits the
     * already-received order to the SAME approval engine, document type and policies as a normal PO,
     * so the ratification appears in the approvals inbox managers already use with its existing
     * audit trail. No new columns.
     *
     * <p>Only valid for an order whose origin is {@code DIRECT_RECEIPT}; idempotent (a second call
     * returns the order unchanged), and never fatal to the receipt that triggered it.
     *
     * @param uid uid of the synthesised purchase order
     */
    PurchaseOrderDto requestDirectReceiptRatification(String uid);
}
