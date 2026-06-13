package com.erp.modules.approvals.service;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.ApprovalResolvedPayload;
import com.erp.modules.approvals.domain.dto.DecideRequest;
import com.erp.modules.approvals.domain.entity.ApprovalDecision;
import com.erp.modules.approvals.domain.entity.ApprovalRequest;
import com.erp.modules.approvals.domain.entity.ApprovalRequestStep;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.modules.approvals.domain.enums.ApprovalStepStatus;
import com.erp.modules.approvals.domain.enums.DecisionAction;
import com.erp.modules.approvals.repository.ApprovalDecisionRepository;
import com.erp.modules.approvals.repository.ApprovalRequestRepository;
import com.erp.modules.approvals.repository.ApprovalRequestStepRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Decision lifecycle for approval requests: approve, reject, recall, admin cancel (ADR-0022 D-6).
 *
 * <p>Every transition is under the request's {@code @Version} guard (NFR-APR-05). Two approvers
 * racing on the same open step: the first commits; the second hits ObjectOptimisticLockingFailureException
 * → one retry, re-reads fresh state, finds the step already decided → 409 "already decided".
 */
@Service
@Transactional
public class ApprovalDecisionServiceImpl implements ApprovalDecisionService {

    private final ApprovalRequestRepository     requestRepo;
    private final ApprovalRequestStepRepository stepRepo;
    private final ApprovalDecisionRepository    decisionRepo;
    private final ApprovalEngineImpl            engine; // for toDto
    private final StepApproverResolver          approverResolver;
    private final ScopeGuard                    scopeGuard;
    private final AuditService                  audit;
    private final OutboxPublisher               outbox;

    public ApprovalDecisionServiceImpl(ApprovalRequestRepository requestRepo,
                                       ApprovalRequestStepRepository stepRepo,
                                       ApprovalDecisionRepository decisionRepo,
                                       ApprovalEngineImpl engine,
                                       StepApproverResolver approverResolver,
                                       ScopeGuard scopeGuard,
                                       AuditService audit,
                                       OutboxPublisher outbox) {
        this.requestRepo      = requestRepo;
        this.stepRepo         = stepRepo;
        this.decisionRepo     = decisionRepo;
        this.engine           = engine;
        this.approverResolver = approverResolver;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
        this.outbox           = outbox;
    }

    @Override
    public ApprovalRequestDto decide(String requestUid, DecideRequest req) {
        return decideWithRetry(requestUid, req, false);
    }

    private ApprovalRequestDto decideWithRetry(String requestUid, DecideRequest req, boolean isRetry) {
        RequestContext.Principal principal = RequestContext.get();
        ApprovalRequest request = requestRepo.findByUid(requestUid)
                .orElseThrow(() -> new NotFoundException("Approval request not found: " + requestUid));
        scopeGuard.assertCanActIn(principal, request.getCompanyId());

        if (request.getStatus().isTerminal()) {
            throw new ConflictException("Request " + requestUid + " is already " + request.getStatus());
        }

        // Find the current open step (lowest-sequence PENDING)
        List<ApprovalRequestStep> pendingSteps = stepRepo.findPendingStepsOrdered(request.getId());
        if (pendingSteps.isEmpty()) {
            throw new ConflictException("No open step found on request " + requestUid);
        }
        ApprovalRequestStep openStep = pendingSteps.get(0);

        // Fine routing check: caller must hold the step's role + SoD
        if (!approverResolver.canDecide(principal, request, openStep)) {
            throw new ConflictException(
                    "You are not authorised to decide this step: you must hold role '"
                    + openStep.getApproverRoleCode() + "' and not be the submitter (SoD)");
        }

        Long actorId = principal.userId();
        Instant now  = Instant.now();

        try {
            // Append the decision
            ApprovalDecision decision = new ApprovalDecision(
                    request.getId(), openStep.getId(),
                    request.getCompanyId(), request.getBranchId(),
                    req.action(), actorId, req.comment(), actorId);
            decisionRepo.save(decision);

            // Close the open step
            openStep.setStatus(req.action() == DecisionAction.APPROVE
                               ? ApprovalStepStatus.APPROVED : ApprovalStepStatus.REJECTED);
            openStep.setResolvedBy(actorId);
            openStep.setResolvedAt(now);
            openStep.setResolvingDecisionId(decision.getId());

            request.setUpdatedBy(actorId);

            if (req.action() == DecisionAction.APPROVE) {
                handleApprove(request, pendingSteps, actorId, now, decision);
            } else {
                handleReject(request, actorId, now);
            }

            audit.record(AuditEvent.of(AuditActions.APPROVAL_STEP_DECIDE, "approval_requests",
                    request.getId(), request.getUid())
                    .detail(Map.of(
                            "action",   req.action().name(),
                            "stepSeq",  String.valueOf(openStep.getSequence()),
                            "stepRole", openStep.getApproverRoleCode())));

            return engine.toDto(request);

        } catch (ObjectOptimisticLockingFailureException ex) {
            if (isRetry) {
                // Second race — the step has already been decided by a concurrent approver
                throw new ConflictException(
                        "Step " + openStep.getSequence() + " on request " + requestUid
                        + " has already been decided by a concurrent approver");
            }
            return decideWithRetry(requestUid, req, true);
        }
    }

    private void handleApprove(ApprovalRequest request,
                               List<ApprovalRequestStep> pendingSteps,
                               Long actorId,
                               Instant now,
                               ApprovalDecision decision) {
        // More than 1 pending → the second one becomes the new open step; request stays PENDING
        if (pendingSteps.size() > 1) {
            // Nothing more to do — the next PENDING step is now automatically the open step
            return;
        }
        // Last step approved — request resolves APPROVED
        request.setStatus(ApprovalRequestStatus.APPROVED);
        request.setResolvedAt(now);
        request.setResolvedBy(actorId);

        publishResolved(request, actorId, now);
    }

    private void handleReject(ApprovalRequest request, Long actorId, Instant now) {
        // Skip all remaining PENDING steps atomically (one reject kills the chain — OQ-APR-04 default).
        // The open step was already set to REJECTED before this call, so only subsequent PENDING steps
        // are affected. Single DML UPDATE avoids intermediate inconsistent state (Finding 1 fix).
        //
        // IMPORTANT: @Modifying(clearAutomatically = true) on updatePendingToSkipped evicts ALL
        // managed entities from the 1st-level cache after the bulk UPDATE. This detaches the
        // `request` entity, so mutations after this call are NOT tracked by dirty-checking and
        // would never be flushed. We must explicitly save the request after updating it to ensure
        // the REJECTED status is persisted within this TX.
        stepRepo.updatePendingToSkipped(request.getId());

        request.setStatus(ApprovalRequestStatus.REJECTED);
        request.setResolvedAt(now);
        request.setResolvedBy(actorId);
        requestRepo.save(request);   // explicit save: entity is detached after cache-clear above

        publishResolved(request, actorId, now);
    }

    @Override
    public ApprovalRequestDto recall(String requestUid) {
        RequestContext.Principal principal = RequestContext.get();
        ApprovalRequest request = requestRepo.findByUid(requestUid)
                .orElseThrow(() -> new NotFoundException("Approval request not found: " + requestUid));
        scopeGuard.assertCanActIn(principal, request.getCompanyId());

        if (request.getStatus().isTerminal()) {
            throw new ConflictException("Cannot recall a terminal request: " + request.getStatus());
        }

        Long actorId = principal.userId();
        // Must be submitter or APPROVALS.ADMIN (the controller @perm gate ensures APPROVALS.REQUEST.VIEW;
        // service additionally checks submitter identity vs admin perm)
        boolean isSubmitter = actorId.equals(request.getSubmittedBy());
        // APPROVALS.ADMIN check is via controller @perm; non-submitter non-admin is blocked at controller
        // (the recall endpoint is scoped with APPROVALS.REQUEST.VIEW but controller recallOwn is checked here)
        if (!isSubmitter) {
            // A non-submitter must hold APPROVALS.ADMIN — this is enforced at the controller level by using
            // the separate cancel endpoint for admins. Recall is strictly submitter-only at the service.
            throw new ConflictException("Only the submitter may recall a request (use cancel for admin override)");
        }

        Instant now = Instant.now();
        skipPendingSteps(request.getId());   // clears 1st-level cache → entity detached
        request.setStatus(ApprovalRequestStatus.RECALLED);
        request.setResolvedAt(now);
        request.setResolvedBy(actorId);
        request.setUpdatedBy(actorId);
        requestRepo.save(request);           // explicit save after cache-clear

        audit.record(AuditEvent.of(AuditActions.APPROVAL_REQUEST_RECALL, "approval_requests",
                request.getId(), request.getUid()));
        publishResolved(request, actorId, now);

        return engine.toDto(request);
    }

    @Override
    public ApprovalRequestDto cancel(String requestUid) {
        RequestContext.Principal principal = RequestContext.get();
        ApprovalRequest request = requestRepo.findByUid(requestUid)
                .orElseThrow(() -> new NotFoundException("Approval request not found: " + requestUid));
        scopeGuard.assertCanActIn(principal, request.getCompanyId());

        if (request.getStatus().isTerminal()) {
            throw new ConflictException("Cannot cancel a terminal request: " + request.getStatus());
        }

        Long actorId = principal.userId();
        Instant now  = Instant.now();
        skipPendingSteps(request.getId());   // clears 1st-level cache → entity detached
        request.setStatus(ApprovalRequestStatus.CANCELLED);
        request.setResolvedAt(now);
        request.setResolvedBy(actorId);
        request.setUpdatedBy(actorId);
        requestRepo.save(request);           // explicit save after cache-clear

        audit.record(AuditEvent.of(AuditActions.APPROVAL_REQUEST_CANCEL, "approval_requests",
                request.getId(), request.getUid()));
        publishResolved(request, actorId, now);

        return engine.toDto(request);
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalRequestDto getByUid(String uid) {
        RequestContext.Principal principal = RequestContext.get();
        ApprovalRequest request = requestRepo.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Approval request not found: " + uid));
        scopeGuard.assertCanActIn(principal, request.getCompanyId());
        return engine.toDto(request);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApprovalRequestDto> listByCompany(Long companyId, Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        return requestRepo.findByCompanyId(companyId, pageable).map(engine::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApprovalRequestDto> listByCompanyAndStatus(Long companyId, String status,
                                                            Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        ApprovalRequestStatus statusEnum = ApprovalRequestStatus.valueOf(status.toUpperCase());
        return requestRepo.findByCompanyIdAndStatus(companyId, statusEnum, pageable).map(engine::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApprovalRequestDto> inbox(Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        if (principal == null) throw new com.erp.platform.common.api.ForbiddenException("Not authenticated");
        scopeGuard.assertCanActIn(principal, principal.companyId());

        List<String> roleCodes = approverResolver.resolveRoleCodes(principal.userId(), principal.companyId());
        if (roleCodes.isEmpty()) {
            return Page.empty(pageable);
        }
        List<Long> requestIds = stepRepo.findInboxRequestIds(
                principal.companyId(), roleCodes, principal.userId());
        if (requestIds.isEmpty()) {
            return Page.empty(pageable);
        }
        // Fetch matching PENDING requests by their ids and return as a PageImpl
        // (inbox ids are already filtered by role + SoD via the step query)
        List<ApprovalRequestDto> content = requestRepo.findAllById(requestIds)
                .stream()
                .filter(r -> r.getStatus() == ApprovalRequestStatus.PENDING)
                .map(engine::toDto)
                .toList();
        int start = (int) pageable.getOffset();
        int end   = Math.min(start + pageable.getPageSize(), content.size());
        List<ApprovalRequestDto> page = (start >= content.size()) ? List.of() : content.subList(start, end);
        return new org.springframework.data.domain.PageImpl<>(page, pageable, content.size());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void skipPendingSteps(Long requestId) {
        // Single atomic UPDATE — eliminates entity loop inconsistency risk (Finding 1 fix).
        stepRepo.updatePendingToSkipped(requestId);
    }

    private void publishResolved(ApprovalRequest request, Long resolvedBy, Instant resolvedAt) {
        outbox.publish(DomainEventType.APPROVAL_RESOLVED, DomainEventType.AGG_APPROVAL_REQUEST,
                request.getId(), request.getUid(), request.getCompanyId(), request.getBranchId(),
                new ApprovalResolvedPayload(
                        request.getUid(), request.getDocumentType(), request.getDocumentUid(),
                        request.getCompanyId(), request.getBranchId(),
                        request.getStatus(), resolvedBy, resolvedAt));
    }
}
