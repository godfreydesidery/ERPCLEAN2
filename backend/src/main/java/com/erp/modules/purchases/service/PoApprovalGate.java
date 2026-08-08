package com.erp.modules.purchases.service;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.SubmitForApprovalRequest;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.modules.approvals.service.ApprovalEngine;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseSettings;
import com.erp.modules.purchases.domain.enums.PoApprovalStatus;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
import com.erp.modules.purchases.repository.PurchaseSettingsRepository;
import com.erp.platform.common.money.CurrencyCode;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin PO approval seam (ADR-0027 D-6).
 *
 * <p>Decides whether a PO requires approval (threshold gate) and routes to the approval engine.
 * If no PurchaseSettings row exists, gate is disabled (default: all POs auto-approved).
 *
 * <p>The permission-gated fallback (approvePo / rejectPo) is in {@link PurchaseOrderServiceImpl}.
 */
@Component
public class PoApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(PoApprovalGate.class);
    private static final String DOC_TYPE = "PURCHASE_ORDER";

    private final PurchaseSettingsRepository settings;
    private final ApprovalEngine             approvalEngine;

    public PoApprovalGate(PurchaseSettingsRepository settings,
                           ApprovalEngine approvalEngine) {
        this.settings       = settings;
        this.approvalEngine = approvalEngine;
    }

    /**
     * Determines whether the PO needs approval given current settings.
     * Returns {@code true} if approval is required AND not yet obtained.
     *
     * @param po        the placed PO
     * @param branchUid uid of the branch for the engine policy lookup
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requiresApproval(PurchaseOrder po, String branchUid) {
        if (isExemptFromPreApproval(po)) {
            return false;
        }

        Optional<PurchaseSettings> cfg = settings.findByCompanyId(po.getCompanyId());
        if (cfg.isEmpty() || !cfg.get().isPoApprovalEnabled()) {
            return false;
        }

        PurchaseSettings s = cfg.get();
        BigDecimal threshold = s.getPoApprovalThresholdAmount();
        BigDecimal poTotal   = po.getOrderTotalAmount() != null ? po.getOrderTotalAmount() : BigDecimal.ZERO;

        if (threshold != null && poTotal.compareTo(threshold) < 0) {
            return false;   // below threshold — auto-approved
        }

        return true;
    }

    /**
     * Whether this order is exempt from <b>pre</b>-approval at placement (owner decision,
     * Kilimanjaro K3, 2026-08-08).
     *
     * <p><b>Why an exemption exists at all.</b> A direct goods receipt
     * ({@link PurchaseOrderOrigin#DIRECT_RECEIPT}) synthesises its backing order and places it
     * inside the SAME transaction as the receipt — the order does not exist until that call creates
     * it. So when the threshold gate fired here there was literally no moment at which anyone could
     * have approved it: the request was structurally unsatisfiable and every direct receipt in a
     * company with PO approval enabled failed with HTTP 409. (Verified live: zero rows had ever
     * been created.)
     *
     * <p><b>Why exempting is the right answer, not a loophole.</b> The goods are already on the
     * loading dock. Refusing to record them does not un-buy them — it only makes stock wrong, which
     * is the worse failure and is exactly what pushed this client onto the stock-adjustment
     * workaround that mis-values inventory. The control therefore becomes <b>detective rather than
     * preventive</b>: the receipt lands, and
     * {@link PurchaseOrderService#requestDirectReceiptRatification(String)} immediately raises a
     * RATIFICATION request in the same approvals inbox managers already use, so the spend is still
     * reviewed — after the event, which is the only point at which review is possible.
     *
     * <p><b>Why it cannot leak into the normal PO path.</b> {@code origin} is stamped once, at
     * construction, by {@code PurchaseOrder#markSynthesisedByDirectReceipt()}; that method is called
     * from exactly one place — {@code PurchaseOrderServiceImpl#createWithOrigin} when handed
     * {@code DIRECT_RECEIPT} — and the only caller that passes {@code DIRECT_RECEIPT} is
     * {@code DirectGoodsReceiptServiceImpl}. Nothing on the buyer's REST surface can set it, and no
     * update path mutates it, so a manually raised PO can never reach this branch.
     */
    public boolean isExemptFromPreApproval(PurchaseOrder po) {
        return po != null && po.getOrigin() == PurchaseOrderOrigin.DIRECT_RECEIPT;
    }

    /**
     * Submits the PO to the approval engine and stores the approval_request_uid on the PO.
     * Sets approval_status = PENDING on the PO entity (caller must save).
     *
     * @param po        the PO to submit
     * @param branchUid uid of the branch
     * @return the submitted request dto
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequestDto submit(PurchaseOrder po, String branchUid) {
        return submitInternal(po, branchUid,
                "PO " + po.getOrderNumber() + " — " + po.getSupplierName());
    }

    /**
     * Post-hoc <b>ratification</b> of a direct goods receipt (K3 owner decision, 2026-08-08).
     *
     * <p>Same document type, same engine, same policies and the same approvals inbox as a normal PO
     * submission — deliberately, so the review lands where managers already look and inherits the
     * existing decision audit trail. Only the inbox summary differs, because the approver is being
     * asked a different question: the goods are already in stock, so a rejection is a dispute to
     * chase (return / debit note), not a purchase that can be prevented.
     *
     * <p>No new columns: the verdict lives on the engine's own request row and is mirrored onto
     * {@code purchase_orders.approval_status} by the existing reconcile-on-read seam.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequestDto submitRatification(PurchaseOrder po, String branchUid) {
        return submitInternal(po, branchUid,
                "RATIFY goods already received (no LPO) — " + po.getOrderNumber()
                        + " — " + po.getSupplierName());
    }

    private ApprovalRequestDto submitInternal(PurchaseOrder po, String branchUid, String summary) {
        RequestContext.Principal principal = RequestContext.get();
        Long actorId = principal != null ? principal.userId() : null;

        SubmitForApprovalRequest req = new SubmitForApprovalRequest(
                DOC_TYPE,
                po.getUid(),
                po.getOrderTotalAmount() != null ? po.getOrderTotalAmount() : BigDecimal.ZERO,
                CurrencyCode.value(po.getCurrency()),
                po.getCompanyId(),
                branchUid,
                actorId != null ? actorId : 0L,
                summary);

        ApprovalRequestDto result = approvalEngine.submitForApproval(req);

        po.setApprovalRequestUid(result.uid());
        po.setApprovalStatus(
                result.status() == ApprovalRequestStatus.APPROVED
                        ? PoApprovalStatus.APPROVED
                        : PoApprovalStatus.PENDING);

        log.debug("PoApprovalGate: PO {} submitted for approval, request uid={}, engine status={}",
                po.getUid(), result.uid(), result.status());

        return result;
    }

    /**
     * Query the current approval state. Empty = not submitted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ApprovalRequestDto> queryState(String poUid, Long companyId) {
        return approvalEngine.getApprovalState(DOC_TYPE, poUid, companyId);
    }

    /**
     * Map approval engine status to PoApprovalStatus enum (for polling / webhook update).
     */
    public static PoApprovalStatus fromEngineStatus(ApprovalRequestStatus engineStatus) {
        return switch (engineStatus) {
            case APPROVED -> PoApprovalStatus.APPROVED;
            case REJECTED -> PoApprovalStatus.REJECTED;
            default       -> PoApprovalStatus.PENDING;
        };
    }
}
