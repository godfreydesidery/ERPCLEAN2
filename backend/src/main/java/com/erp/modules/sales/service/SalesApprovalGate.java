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
}
