package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.enums.DirectReceiptRatificationState;
import com.erp.modules.purchases.domain.dto.PurchaseOrderApprovalSnapshotDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.enums.PoApprovalStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * Tells AP whether a bill's backing purchase order is a direct goods receipt that nobody has
 * ratified yet (K3 follow-up, Kilimanjaro 2026-08-08).
 *
 * <p><b>Background.</b> A direct receipt — goods that arrive with no prior LPO — is deliberately
 * EXEMPT from PO pre-approval: the goods are already on the loading dock and refusing to record them
 * only makes stock wrong. Instead {@code PurchaseOrderService#requestDirectReceiptRatification}
 * raises a post-hoc RATIFICATION request in the same approvals inbox managers already use
 * ({@code PoApprovalGate#isExemptFromPreApproval}). That makes the control <b>detective</b>.
 *
 * <p><b>The gap this closes.</b> A detective control is only a control if the money waits for it.
 * Nothing stopped the supplier bill for an unratified receipt being entered, matched and PAID before
 * the manager ever saw the request, so on the one side that matters — cash — the control was purely
 * advisory. This class is the seam that lets AP see that state.
 *
 * <p><b>Where the block goes, and why not earlier.</b> Bill ENTRY stays open: the supplier invoice is
 * a real document that arrived, an AP clerk must be able to record it, and a payable you refuse to
 * record is invisible — a worse control failure than one you can see. Three-way MATCH stays open too:
 * it recognises a liability that genuinely exists and moves no cash. PAYMENT is refused, because that
 * is the only step that is irreversible. The bill carries the state on its DTO from the moment it is
 * entered, so the refusal at payment time is never a surprise.
 *
 * <p><b>Module boundary.</b> Reads Purchases only through {@link PurchaseMatchReader} — a DTO + enum
 * seam, no entity and no repository (ModuleBoundaryTest, NFR-AP-06).
 */
@Component
public class DirectReceiptRatificationGuard {

    private static final String APPROVED_STATUS = "APPROVED";
    private static final String REJECTED_STATUS = "REJECTED";

    private final PurchaseMatchReader purchases;

    public DirectReceiptRatificationGuard(PurchaseMatchReader purchases) {
        this.purchases = purchases;
    }

    /**
     * Resolves the ratification state of the order backing a bill.
     *
     * <p>Returns {@link DirectReceiptRatificationState#NOT_APPLICABLE} when there is no backing
     * order, when the order is MANUAL, or when the order cannot be read at all
     * ({@link PurchaseMatchReader#findPo} is fail-open by contract) — a gate that fires on a lookup
     * blip would strand legitimate payables with nothing an operator could do about it.
     *
     * <p>A null {@code approvalStatus} on the wire means the PO's stored status is
     * {@code NOT_REQUIRED}, i.e. no approval is in play at all. That is unreachable for a direct
     * receipt (the ratification request is raised unconditionally, inside the receipt's own
     * transaction) and it is deliberately treated as NOT_APPLICABLE rather than as a block: a gate
     * with no request behind it is a gate nobody could ever satisfy.
     */
    public DirectReceiptRatificationState stateFor(String purchaseOrderUid) {
        if (purchaseOrderUid == null || purchaseOrderUid.isBlank()) {
            return DirectReceiptRatificationState.NOT_APPLICABLE;
        }
        PurchaseOrderDto po = purchases.findPo(purchaseOrderUid).orElse(null);
        return po == null
                ? DirectReceiptRatificationState.NOT_APPLICABLE
                : map(po.origin(), po.approvalStatus());
    }

    /**
     * Maps one order's provenance + approval status onto the AP-facing state.
     *
     * @param approvalStatus the engine-facing status NAME, or null when no approval is in play
     *                       (a PO that is {@code NOT_REQUIRED} collapses to null before it gets here)
     */
    private static DirectReceiptRatificationState map(PurchaseOrderOrigin origin,
                                                       String approvalStatus) {
        if (origin != PurchaseOrderOrigin.DIRECT_RECEIPT || approvalStatus == null) {
            return DirectReceiptRatificationState.NOT_APPLICABLE;
        }
        if (APPROVED_STATUS.equals(approvalStatus)) {
            return DirectReceiptRatificationState.RATIFIED;
        }
        return REJECTED_STATUS.equals(approvalStatus)
                ? DirectReceiptRatificationState.RATIFICATION_REFUSED
                : DirectReceiptRatificationState.AWAITING_RATIFICATION;
    }

    /** Convenience: true when the bill's backing receipt still needs a manager's decision. */
    public boolean blocksPayment(String purchaseOrderUid) {
        return stateFor(purchaseOrderUid).blocksPayment();
    }

    /**
     * Resolves the ratification state of a whole page of bills in ONE purchase-order read, without
     * touching anything.
     *
     * <p><b>Why a listing may not use {@link #stateFor(String)}.</b> That path goes through
     * {@link PurchaseMatchReader#findPo}, and the Purchases detail read is not a pure read: it polls
     * the approval engine, writes the answer back onto the order and records an audit row. From a
     * {@code readOnly} transaction — which is what a listing is — Hibernate runs in MANUAL flush
     * mode, so that write is silently discarded: the reconcile appears to happen, persists nothing,
     * and costs an engine round-trip per row on the way. This method reads the stored facts
     * instead: one query for the page, no writes, nothing to drop.
     *
     * <p>The consequence is deliberate: a listing shows the status as STORED. If a manager has just
     * ratified a delivery in the Approvals inbox and nothing has reconciled that order yet, the list
     * can still read AWAITING for a while. That is a stale badge, not a wrong decision — the
     * payment gate ({@link #newLookup()}) reconciles before it refuses cash, so the money is never
     * held on stale information and never released on it either.
     *
     * @param purchaseOrderUids the backing-order uid of every bill on the page; nulls, blanks and
     *                          duplicates are fine and cost nothing
     */
    public Snapshot snapshotFor(Collection<String> purchaseOrderUids) {
        if (purchaseOrderUids == null || purchaseOrderUids.isEmpty()) {
            return new Snapshot(Map.of());
        }
        Map<String, PurchaseOrderApprovalSnapshotDto> resolved =
                purchases.findApprovalSnapshots(purchaseOrderUids);
        if (resolved == null || resolved.isEmpty()) {
            return new Snapshot(Map.of());   // fail-open: a listing renders without badges
        }
        Map<String, DirectReceiptRatificationState> states = new HashMap<>();
        resolved.forEach((uid, snap) ->
                states.put(uid, map(snap.origin(), approvalStatusName(snap))));
        return new Snapshot(states);
    }

    /**
     * The engine-facing status name, or null when no approval is in play.
     *
     * <p>{@code NOT_REQUIRED} collapses to null exactly as {@code PurchaseOrderDto} does on the
     * wire, so both read paths answer the same thing for the same order. It is unreachable for a
     * direct receipt (the ratification request is raised unconditionally, inside the receipt's own
     * transaction) and is treated as NOT_APPLICABLE rather than as a block: a gate with no request
     * behind it is a gate nobody could ever satisfy.
     */
    private static String approvalStatusName(PurchaseOrderApprovalSnapshotDto snapshot) {
        PoApprovalStatus status = snapshot.approvalStatus();
        return status == null || status == PoApprovalStatus.NOT_REQUIRED ? null : status.name();
    }

    /**
     * The ratification state of one page of bills, resolved up front by
     * {@link #snapshotFor(Collection)}.
     *
     * <p>Reads nothing: an order that was not resolved — unknown, out of the caller's scope, or a
     * seam that failed — answers NOT_APPLICABLE, so the page still renders. Cannot fall through to
     * the reconciling read by accident, which is the whole point of it being a separate type from
     * {@link Lookup}.
     */
    public static final class Snapshot {

        private final Map<String, DirectReceiptRatificationState> states;

        private Snapshot(Map<String, DirectReceiptRatificationState> states) {
            this.states = states;
        }

        public DirectReceiptRatificationState stateFor(String purchaseOrderUid) {
            if (purchaseOrderUid == null || purchaseOrderUid.isBlank()) {
                return DirectReceiptRatificationState.NOT_APPLICABLE;
            }
            return states.getOrDefault(purchaseOrderUid,
                    DirectReceiptRatificationState.NOT_APPLICABLE);
        }
    }

    /**
     * User-facing refusal text. Says what happened and what to do next; names no order, no uid and
     * no internal state (error-hygiene rule).
     */
    public static String refusalMessage(DirectReceiptRatificationState state) {
        return state == DirectReceiptRatificationState.RATIFICATION_REFUSED
                ? "These goods were received without a purchase order and a manager did not ratify"
                        + " the delivery. Settle it with the supplier, or have the delivery approved,"
                        + " before paying."
                : "These goods were received without a purchase order and are still waiting for a"
                        + " manager to ratify the delivery. Once it is approved this bill can be paid.";
    }

    /**
     * A short-lived, per-call memo over {@link #stateFor(String)} — for the CONTROL POINT, i.e.
     * payment release.
     *
     * <p>Every miss reconciles the backing order against the approval engine, which is exactly what
     * a decision to move cash needs and exactly what a read-only listing must not do; the caller
     * must therefore be in a read-write transaction. Screens that only display the state use
     * {@link #snapshotFor(Collection)} instead.
     *
     * <p>Each miss costs a purchase-order read, so a payment run that touches the same order twice
     * should not pay for it twice. Not thread-safe and not a cache with a lifetime — create one,
     * use it for a single service call, drop it.
     */
    public Lookup newLookup() {
        return new Lookup();
    }

    /** @see #newLookup() */
    public final class Lookup {

        private final Map<String, DirectReceiptRatificationState> seen = new HashMap<>();

        private Lookup() {
        }

        public DirectReceiptRatificationState stateFor(String purchaseOrderUid) {
            if (purchaseOrderUid == null || purchaseOrderUid.isBlank()) {
                return DirectReceiptRatificationState.NOT_APPLICABLE;
            }
            return seen.computeIfAbsent(purchaseOrderUid,
                    DirectReceiptRatificationGuard.this::stateFor);
        }

        public boolean blocksPayment(String purchaseOrderUid) {
            return stateFor(purchaseOrderUid).blocksPayment();
        }
    }
}
