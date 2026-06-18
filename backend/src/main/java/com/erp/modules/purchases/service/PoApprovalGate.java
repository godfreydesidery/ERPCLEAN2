package com.erp.modules.purchases.service;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.SubmitForApprovalRequest;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.modules.approvals.service.ApprovalEngine;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseSettings;
import com.erp.modules.purchases.domain.enums.PoApprovalStatus;
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
     * Submits the PO to the approval engine and stores the approval_request_uid on the PO.
     * Sets approval_status = PENDING on the PO entity (caller must save).
     *
     * @param po        the PO to submit
     * @param branchUid uid of the branch
     * @return the submitted request dto
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequestDto submit(PurchaseOrder po, String branchUid) {
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
                "PO " + po.getOrderNumber() + " — " + po.getSupplierName());

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
