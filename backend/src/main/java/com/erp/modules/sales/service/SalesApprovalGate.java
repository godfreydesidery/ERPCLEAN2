package com.erp.modules.sales.service;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.SubmitForApprovalRequest;
import com.erp.modules.approvals.service.ApprovalEngine;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.sales.domain.entity.SalesOrder;
import com.erp.modules.sales.domain.entity.SalesSettings;
import com.erp.modules.sales.repository.SalesSettingsRepository;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Thin SO amount-threshold approval seam (deferred item D-4; extends the engine-derived approval
 * flow shipped in PR #189).
 *
 * <p>Decides whether a SalesOrder requires approval (threshold gate) and routes to the approval
 * engine. If no SalesSettings row exists, the gate is disabled (default: all SOs auto-approved).
 * Mirrors {@link PoApprovalGate} — but nothing is persisted on the SalesOrder entity (the engine
 * remains the sole source of truth, per the PR #189 design); the caller
 * ({@code SalesOrderServiceImpl.doConfirm}) reads the returned/queried state instead.
 */
@Component
public class SalesApprovalGate {

    private static final Logger log = LoggerFactory.getLogger(SalesApprovalGate.class);
    private static final String DOC_TYPE = "SALES_ORDER";

    private final SalesSettingsRepository settings;
    private final ApprovalEngine          approvalEngine;
    private final CustomerRepository      customers;

    public SalesApprovalGate(SalesSettingsRepository settings,
                             ApprovalEngine approvalEngine,
                             CustomerRepository customers) {
        this.settings       = settings;
        this.approvalEngine = approvalEngine;
        this.customers      = customers;
    }

    /**
     * Determines whether the order needs approval given current settings.
     * Returns {@code true} if approval is required.
     *
     * @param order     the order being confirmed
     * @param branchUid uid of the branch for the engine policy lookup (unused here; carried for
     *                  parity with {@link #submit} and {@link PoApprovalGate#requiresApproval})
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean requiresApproval(SalesOrder order, String branchUid) {
        Optional<SalesSettings> cfg = settings.findByCompanyId(order.getCompanyId());
        if (cfg.isEmpty() || !cfg.get().isSoApprovalEnabled()) {
            return false;
        }

        SalesSettings s = cfg.get();
        BigDecimal threshold  = s.getSoApprovalThresholdAmount();
        BigDecimal orderTotal = order.getGrossTotalAmount() != null
                ? order.getGrossTotalAmount() : BigDecimal.ZERO;

        if (threshold != null && orderTotal.compareTo(threshold) < 0) {
            return false;   // below threshold — auto-approved
        }

        return true;
    }

    /**
     * Submits the order to the approval engine. Builds the exact same {@link SubmitForApprovalRequest}
     * shape as {@code SalesOrderServiceImpl.submitForApproval} (same doc-type constant, same
     * company-scoped customer finder for the summary label).
     *
     * @param order     the order to submit
     * @param branchUid uid of the branch
     * @return the submitted request dto (status may already be APPROVED if the engine found no
     *         matching policy — BR-APR-09, OQ-APR-01 default)
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public ApprovalRequestDto submit(SalesOrder order, String branchUid) {
        RequestContext.Principal principal = RequestContext.get();
        Long actorId = principal != null ? principal.userId() : null;

        String customerName = customers.findByCompanyIdAndId(order.getCompanyId(), order.getCustomerId())
                .map(Customer::getDisplayName).orElse(null);
        BigDecimal amount = order.getGrossTotalAmount() != null
                ? order.getGrossTotalAmount() : BigDecimal.ZERO;
        String summary = customerName != null
                ? "SO " + order.getOrderNumber() + " — " + customerName
                : "SO " + order.getOrderNumber();

        SubmitForApprovalRequest req = new SubmitForApprovalRequest(
                DOC_TYPE,
                order.getUid(),
                amount,
                order.getCurrency().value(),
                order.getCompanyId(),
                branchUid,
                actorId != null ? actorId : 0L,
                summary);

        ApprovalRequestDto result = approvalEngine.submitForApproval(req);

        log.debug("SalesApprovalGate: SO {} submitted for approval, request uid={}, engine status={}",
                order.getUid(), result.uid(), result.status());

        return result;
    }

    /**
     * Query the current approval state. Empty = not submitted.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public Optional<ApprovalRequestDto> queryState(String orderUid, Long companyId) {
        return approvalEngine.getApprovalState(DOC_TYPE, orderUid, companyId);
    }

    /**
     * Submits the order to the approval engine in an INDEPENDENT transaction ({@code
     * REQUIRES_NEW}) — the written {@code ApprovalRequest} commits on its own, regardless of what
     * the caller's transaction does afterwards.
     *
     * <p>Fixes a stuck-forever bug (persona UAT I3): {@code SalesOrderServiceImpl.doConfirm} runs
     * as one transaction and, for an over-threshold order, submits for approval and then
     * deliberately throws to BLOCK the confirm until a human decides. Before this method existed,
     * that throw rolled back the WHOLE transaction — including the just-written pending approval
     * request — so the order could never reach an approver and stayed in DRAFT forever. Delegates
     * to {@link #submit} but on its own physical transaction (mirrors the established {@code
     * AuditService#recordIndependent} pattern for the same class of problem).
     *
     * <p>Persona UAT follow-up R1: if the engine found no matching {@code SALES_ORDER} policy, it
     * auto-approves by default ({@code result.autoApproved() == true}) — for an over-threshold
     * order that is a misconfiguration, not a real approval. Leaving that auto-approved (terminal)
     * request durable would poison every future confirm attempt, even after an administrator adds a
     * matching policy (the request is already terminal, so it cannot be re-submitted). So in that
     * case this method throws {@link ApprovalPolicyMissingException} INSTEAD of returning — which,
     * thrown from inside this {@code REQUIRES_NEW} boundary, rolls back only this independent
     * transaction and leaves NO durable request behind. The genuine policy-matched case (result not
     * auto-approved) still returns normally and commits independently, exactly as before.
     *
     * @param order     the order being confirmed
     * @param branchUid uid of the branch for the engine's summary/policy lookup
     * @return the submitted (or, on a concurrent race, the already-existing) request dto — never
     *         an auto-approved-by-default one (see above)
     * @throws ApprovalPolicyMissingException when the engine had no matching policy and would only
     *         have auto-approved by default; this transaction is rolled back before the exception
     *         propagates
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ApprovalRequestDto submitIndependent(SalesOrder order, String branchUid) {
        ApprovalRequestDto result = submit(order, branchUid);
        if (result.autoApproved()) {
            throw new ApprovalPolicyMissingException();
        }
        return result;
    }
}
