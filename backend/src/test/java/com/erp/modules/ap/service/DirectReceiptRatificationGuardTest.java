package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.ap.domain.enums.DirectReceiptRatificationState;
import com.erp.modules.purchases.domain.dto.PurchaseOrderApprovalSnapshotDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.enums.PoApprovalStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
import com.erp.modules.purchases.domain.enums.PurchaseOrderStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;

/**
 * K3 follow-up — the AP side of the direct-receipt ratification control.
 *
 * <p>A direct goods receipt is exempt from PO pre-approval and ratified afterwards instead; these
 * tests pin the mapping from the backing order's origin + approval status onto the state AP uses to
 * decide whether cash may leave.
 */
class DirectReceiptRatificationGuardTest {

    private static final String PO_UID = "PO-UID-1";

    private final PurchaseMatchReader reader = mock(PurchaseMatchReader.class);
    private final DirectReceiptRatificationGuard guard = new DirectReceiptRatificationGuard(reader);

    // -------------------------------------------------------------------------
    // The gate does not apply
    // -------------------------------------------------------------------------

    @Test
    void noBackingOrder_isNotApplicable() {
        assertThat(guard.stateFor(null)).isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
        assertThat(guard.stateFor("  ")).isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
        verify(reader, never()).findPo(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void manualOrder_awaitingItsOwnApproval_isStillNotApplicable() {
        // A buyer's PO has its own PREVENTIVE approval gate before placement. This control is only
        // about receipts that skipped that gate, so it must not second-guess the normal path.
        givenPo(PurchaseOrderOrigin.MANUAL, "PENDING");

        assertThat(guard.stateFor(PO_UID)).isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
        assertThat(guard.blocksPayment(PO_UID)).isFalse();
    }

    @Test
    void unreadableOrder_failsOpen() {
        // findPo is fail-open by contract. A gate that fires on a lookup blip would strand a real
        // payable with nothing an operator could do about it.
        when(reader.findPo(PO_UID)).thenReturn(Optional.empty());

        assertThat(guard.stateFor(PO_UID)).isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
    }

    @Test
    void directReceiptWithNoApprovalInPlay_failsOpen() {
        // null on the wire == NOT_REQUIRED, i.e. no request exists. Blocking would be a gate nobody
        // could ever satisfy.
        givenPo(PurchaseOrderOrigin.DIRECT_RECEIPT, null);

        assertThat(guard.stateFor(PO_UID)).isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
    }

    // -------------------------------------------------------------------------
    // The gate applies
    // -------------------------------------------------------------------------

    @Test
    void directReceiptPendingRatification_blocksPayment() {
        givenPo(PurchaseOrderOrigin.DIRECT_RECEIPT, "PENDING");

        assertThat(guard.stateFor(PO_UID))
                .isEqualTo(DirectReceiptRatificationState.AWAITING_RATIFICATION);
        assertThat(guard.blocksPayment(PO_UID)).isTrue();
    }

    @Test
    void directReceiptRatificationRefused_blocksPayment() {
        givenPo(PurchaseOrderOrigin.DIRECT_RECEIPT, "REJECTED");

        assertThat(guard.stateFor(PO_UID))
                .isEqualTo(DirectReceiptRatificationState.RATIFICATION_REFUSED);
        assertThat(guard.blocksPayment(PO_UID)).isTrue();
    }

    @Test
    void directReceiptRatified_paysLikeAnyOtherBill() {
        givenPo(PurchaseOrderOrigin.DIRECT_RECEIPT, "APPROVED");

        assertThat(guard.stateFor(PO_UID)).isEqualTo(DirectReceiptRatificationState.RATIFIED);
        assertThat(guard.blocksPayment(PO_UID)).isFalse();
    }

    // -------------------------------------------------------------------------
    // Messages + memo
    // -------------------------------------------------------------------------

    @Test
    void refusalMessages_tellTheUserWhatToDoAndLeakNothing() {
        String pending = DirectReceiptRatificationGuard.refusalMessage(
                DirectReceiptRatificationState.AWAITING_RATIFICATION);
        String refused = DirectReceiptRatificationGuard.refusalMessage(
                DirectReceiptRatificationState.RATIFICATION_REFUSED);

        assertThat(pending).contains("waiting for a manager to ratify");
        assertThat(refused).contains("did not ratify");
        assertThat(pending).doesNotContain(PO_UID);
        assertThat(refused).doesNotContain(PO_UID);
        // No enum names, class names or status codes on the way to the user.
        assertThat(pending + refused)
                .doesNotContain("DIRECT_RECEIPT")
                .doesNotContain("APPROVED")
                .doesNotContain("PENDING")
                .doesNotContain("uid");
    }

    @Test
    void lookup_readsEachOrderOnce() {
        givenPo(PurchaseOrderOrigin.DIRECT_RECEIPT, "PENDING");
        DirectReceiptRatificationGuard.Lookup lookup = guard.newLookup();

        assertThat(lookup.blocksPayment(PO_UID)).isTrue();
        assertThat(lookup.blocksPayment(PO_UID)).isTrue();
        assertThat(lookup.blocksPayment(null)).isFalse();

        verify(reader, times(1)).findPo(PO_UID);
    }

    // -------------------------------------------------------------------------
    // snapshotFor — the read path (no reconcile, one call for a page)
    // -------------------------------------------------------------------------

    @Test
    void snapshotFor_resolvesAWholePageInOneCall_andNeverTouchesTheReconcilingRead() {
        // findPo is the Purchases DETAIL read: it polls the approval engine, writes the answer back
        // onto the order and records an audit row. A listing is @Transactional(readOnly = true),
        // where Hibernate's MANUAL flush mode drops that write without a sound — so the read path
        // must not go anywhere near it, and must not pay an engine round-trip per row either.
        String otherUid = "PO-UID-2";
        when(reader.findApprovalSnapshots(anyCollection())).thenReturn(Map.of(
                PO_UID,    snapshot(PO_UID,    PurchaseOrderOrigin.DIRECT_RECEIPT, PoApprovalStatus.PENDING),
                otherUid,  snapshot(otherUid,  PurchaseOrderOrigin.MANUAL,         PoApprovalStatus.PENDING)));

        DirectReceiptRatificationGuard.Snapshot states =
                guard.snapshotFor(List.of(PO_UID, otherUid, PO_UID));

        assertThat(states.stateFor(PO_UID))
                .isEqualTo(DirectReceiptRatificationState.AWAITING_RATIFICATION);
        assertThat(states.stateFor(otherUid))
                .isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
        verify(reader, times(1)).findApprovalSnapshots(anyCollection());
        verify(reader, never()).findPo(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void snapshotFor_mapsEveryTerminalStateTheSameWayTheDetailReadDoes() {
        // The two read paths must never disagree about the same order.
        assertThat(stateFromSnapshot(PurchaseOrderOrigin.DIRECT_RECEIPT, PoApprovalStatus.APPROVED))
                .isEqualTo(DirectReceiptRatificationState.RATIFIED);
        assertThat(stateFromSnapshot(PurchaseOrderOrigin.DIRECT_RECEIPT, PoApprovalStatus.REJECTED))
                .isEqualTo(DirectReceiptRatificationState.RATIFICATION_REFUSED);
        assertThat(stateFromSnapshot(PurchaseOrderOrigin.DIRECT_RECEIPT, PoApprovalStatus.PENDING))
                .isEqualTo(DirectReceiptRatificationState.AWAITING_RATIFICATION);
        assertThat(stateFromSnapshot(PurchaseOrderOrigin.MANUAL, PoApprovalStatus.PENDING))
                .isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
    }

    @Test
    void snapshotFor_noApprovalInPlay_failsOpen() {
        // NOT_REQUIRED is what the detail read collapses to null on the wire: no request exists, so
        // blocking would be a gate nobody could ever satisfy.
        assertThat(stateFromSnapshot(PurchaseOrderOrigin.DIRECT_RECEIPT, PoApprovalStatus.NOT_REQUIRED))
                .isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
        assertThat(stateFromSnapshot(PurchaseOrderOrigin.DIRECT_RECEIPT, null))
                .isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
    }

    @Test
    void snapshotFor_unresolvedOrder_isNotApplicableSoThePageStillRenders() {
        // The seam is fail-open by contract: an order that is unknown, out of the caller's scope, or
        // simply unreadable must cost a badge, never the page.
        when(reader.findApprovalSnapshots(anyCollection())).thenReturn(Map.of());

        DirectReceiptRatificationGuard.Snapshot states = guard.snapshotFor(List.of(PO_UID));

        assertThat(states.stateFor(PO_UID)).isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
        assertThat(states.stateFor(null)).isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
        assertThat(states.stateFor("  ")).isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
    }

    @Test
    void snapshotFor_emptyPage_readsNothingAtAll() {
        assertThat(guard.snapshotFor(List.of()).stateFor(PO_UID))
                .isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);
        assertThat(guard.snapshotFor(null).stateFor(PO_UID))
                .isEqualTo(DirectReceiptRatificationState.NOT_APPLICABLE);

        verify(reader, never()).findApprovalSnapshots(anyCollection());
        verify(reader, never()).findPo(org.mockito.ArgumentMatchers.anyString());
    }

    // -------------------------------------------------------------------------

    private DirectReceiptRatificationState stateFromSnapshot(PurchaseOrderOrigin origin,
                                                             PoApprovalStatus approvalStatus) {
        when(reader.findApprovalSnapshots(anyCollection()))
                .thenReturn(Map.of(PO_UID, snapshot(PO_UID, origin, approvalStatus)));
        return guard.snapshotFor(List.of(PO_UID)).stateFor(PO_UID);
    }

    private static PurchaseOrderApprovalSnapshotDto snapshot(String uid,
                                                             PurchaseOrderOrigin origin,
                                                             PoApprovalStatus approvalStatus) {
        return new PurchaseOrderApprovalSnapshotDto(uid, 10L, origin, approvalStatus);
    }

    private void givenPo(PurchaseOrderOrigin origin, String approvalStatus) {
        when(reader.findPo(PO_UID)).thenReturn(Optional.of(po(origin, approvalStatus)));
    }

    static PurchaseOrderDto po(PurchaseOrderOrigin origin, String approvalStatus) {
        return new PurchaseOrderDto(
                1L, PO_UID, 10L, 20L, "PO-0001",
                PurchaseOrderStatus.ORDERED, origin,
                30L, "SUP-1", "Test Supplier",
                "TZS", new BigDecimal("1000"),
                null, BigDecimal.ZERO, null, null,
                null, null, null, null, null, null, null,
                approvalStatus, List.of());
    }
}
