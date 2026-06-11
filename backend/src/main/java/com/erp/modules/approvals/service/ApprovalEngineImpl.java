package com.erp.modules.approvals.service;

import com.erp.modules.approvals.domain.dto.ApprovalDecisionDto;
import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.ApprovalRequestStepDto;
import com.erp.modules.approvals.domain.dto.ApprovalResolvedPayload;
import com.erp.modules.approvals.domain.dto.ApprovalSubmittedPayload;
import com.erp.modules.approvals.domain.dto.SubmitForApprovalRequest;
import com.erp.modules.approvals.domain.entity.ApprovalPolicy;
import com.erp.modules.approvals.domain.entity.ApprovalPolicyStep;
import com.erp.modules.approvals.domain.entity.ApprovalRequest;
import com.erp.modules.approvals.domain.entity.ApprovalRequestStep;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import com.erp.modules.approvals.repository.ApprovalDecisionRepository;
import com.erp.modules.approvals.repository.ApprovalPolicyStepRepository;
import com.erp.modules.approvals.repository.ApprovalRequestRepository;
import com.erp.modules.approvals.repository.ApprovalRequestStepRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Implementation of the public integration hook (ADR-0022 D-7).
 *
 * <p>submit and getApprovalState are DTO-only — no consumer entity, no consumer repository (D-12).
 * The idempotency backstop is the {@code uq_approval_request_document} UNIQUE constraint; a
 * concurrent double-submit that races past the initial check is caught by the DataIntegrityViolation
 * and returns the existing request (BR-APR-08).
 */
@Service
@Transactional
public class ApprovalEngineImpl implements ApprovalEngine {

    private static final Logger log = LoggerFactory.getLogger(ApprovalEngineImpl.class);

    private final ApprovalRequestRepository     requestRepo;
    private final ApprovalRequestStepRepository stepRepo;
    private final ApprovalDecisionRepository    decisionRepo;
    private final ApprovalPolicyStepRepository  policyStepRepo;
    private final BranchRepository              branchRepo;
    private final ApprovalPolicyMatcher         matcher;
    private final ApprovalNumberGenerator       numberGen;
    private final ScopeGuard                    scopeGuard;
    private final AuditService                  audit;
    private final OutboxPublisher               outbox;

    public ApprovalEngineImpl(ApprovalRequestRepository requestRepo,
                              ApprovalRequestStepRepository stepRepo,
                              ApprovalDecisionRepository decisionRepo,
                              ApprovalPolicyStepRepository policyStepRepo,
                              BranchRepository branchRepo,
                              ApprovalPolicyMatcher matcher,
                              ApprovalNumberGenerator numberGen,
                              ScopeGuard scopeGuard,
                              AuditService audit,
                              OutboxPublisher outbox) {
        this.requestRepo    = requestRepo;
        this.stepRepo       = stepRepo;
        this.decisionRepo   = decisionRepo;
        this.policyStepRepo = policyStepRepo;
        this.branchRepo     = branchRepo;
        this.matcher        = matcher;
        this.numberGen      = numberGen;
        this.scopeGuard     = scopeGuard;
        this.audit          = audit;
        this.outbox         = outbox;
    }

    @Override
    public ApprovalRequestDto submitForApproval(SubmitForApprovalRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, req.companyId());

        // Idempotency check — return existing request if already submitted (BR-APR-08)
        Optional<ApprovalRequest> existing = requestRepo.findByCompanyIdAndDocumentTypeAndDocumentUid(
                req.companyId(), req.documentType(), req.documentUid());
        if (existing.isPresent()) {
            log.debug("submitForApproval idempotency hit: existing request {} for ({}, {})",
                      existing.get().getUid(), req.documentType(), req.documentUid());
            return toDto(existing.get());
        }

        Long branchId = branchRepo.findByUid(req.branchUid())
                .orElseThrow(() -> new com.erp.platform.common.api.NotFoundException(
                        "Branch not found: " + req.branchUid()))
                .getId();

        Long actorId = principal != null ? principal.userId() : req.submittedByUserId();

        // Match against an active policy (D-3)
        Optional<ApprovalPolicy> matchedPolicy = matcher.match(
                req.companyId(), req.documentType(), req.amount(), branchId);

        ApprovalRequest request;
        try {
            if (matchedPolicy.isPresent()) {
                request = submitWithPolicy(req, matchedPolicy.get(), branchId, actorId);
            } else {
                request = submitAutoApproved(req, branchId, actorId);
            }
        } catch (DataIntegrityViolationException ex) {
            // Concurrent double-submit raced to the DB — return the existing request
            log.warn("Concurrent submitForApproval for ({}, {}) — returning existing",
                     req.documentType(), req.documentUid());
            return toDto(requestRepo.findByCompanyIdAndDocumentTypeAndDocumentUid(
                    req.companyId(), req.documentType(), req.documentUid())
                    .orElseThrow(() -> ex));
        }

        return toDto(request);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ApprovalRequestDto> getApprovalState(String documentType, String documentUid,
                                                          Long companyId) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        return requestRepo.findByCompanyIdAndDocumentTypeAndDocumentUid(companyId, documentType, documentUid)
                          .map(this::toDto);
    }

    // ------------------------------------------------------------------
    // Submit helpers
    // ------------------------------------------------------------------

    private ApprovalRequest submitWithPolicy(SubmitForApprovalRequest req,
                                             ApprovalPolicy policy,
                                             Long branchId,
                                             Long actorId) {
        ApprovalRequest request = new ApprovalRequest(
                req.companyId(), branchId,
                req.documentType(), req.documentUid(),
                req.amount(), req.currency(),
                policy.getId(), policy.getUid(),
                req.summary(), req.submittedByUserId(), actorId);
        request.setRequestNumber(numberGen.next(req.companyId()));
        requestRepo.save(request);

        // Snapshot policy steps onto the request (D-5)
        List<ApprovalPolicyStep> policySteps =
                policyStepRepo.findByApprovalPolicyIdOrderBySequence(policy.getId());
        for (ApprovalPolicyStep ps : policySteps) {
            ApprovalRequestStep rs = new ApprovalRequestStep(
                    request.getId(), req.companyId(), branchId,
                    ps.getSequence(), ps.getApproverRoleCode(), actorId);
            stepRepo.save(rs);
        }

        audit.record(AuditEvent.of(AuditActions.APPROVAL_REQUEST_SUBMIT, "approval_requests",
                request.getId(), request.getUid())
                .detail(java.util.Map.of(
                        "documentType", req.documentType(),
                        "documentUid",  req.documentUid(),
                        "policyUid",    policy.getUid())));

        outbox.publish(DomainEventType.APPROVAL_SUBMITTED, DomainEventType.AGG_APPROVAL_REQUEST,
                request.getId(), request.getUid(), req.companyId(), branchId,
                new ApprovalSubmittedPayload(
                        request.getUid(), req.documentType(), req.documentUid(),
                        req.companyId(), branchId, req.amount(),
                        req.submittedByUserId(), request.getSubmittedAt()));

        return request;
    }

    private ApprovalRequest submitAutoApproved(SubmitForApprovalRequest req,
                                               Long branchId,
                                               Long actorId) {
        ApprovalRequest request = new ApprovalRequest(
                req.companyId(), branchId,
                req.documentType(), req.documentUid(),
                req.amount(), req.currency(),
                req.summary(), req.submittedByUserId(), actorId, true);
        request.setRequestNumber(numberGen.next(req.companyId()));
        requestRepo.save(request);

        audit.record(AuditEvent.of(AuditActions.APPROVAL_REQUEST_SUBMIT, "approval_requests",
                request.getId(), request.getUid())
                .detail(java.util.Map.of(
                        "documentType", req.documentType(),
                        "documentUid",  req.documentUid(),
                        "autoApproved", "true")));

        outbox.publish(DomainEventType.APPROVAL_RESOLVED, DomainEventType.AGG_APPROVAL_REQUEST,
                request.getId(), request.getUid(), req.companyId(), branchId,
                new ApprovalResolvedPayload(
                        request.getUid(), req.documentType(), req.documentUid(),
                        req.companyId(), branchId, ApprovalRequestStatus.APPROVED,
                        req.submittedByUserId(), Instant.now()));

        return request;
    }

    // ------------------------------------------------------------------
    // DTO assembly
    // ------------------------------------------------------------------

    ApprovalRequestDto toDto(ApprovalRequest r) {
        List<ApprovalRequestStepDto> stepDtos = stepRepo
                .findByApprovalRequestIdOrderBySequence(r.getId())
                .stream()
                .map(s -> {
                    List<ApprovalDecisionDto> decisionDtos = decisionRepo
                            .findByApprovalRequestStepIdOrderByDecidedAt(s.getId())
                            .stream()
                            .map(d -> new ApprovalDecisionDto(d.getId(), d.getUid(),
                                    d.getApprovalRequestStepId(), d.getAction(),
                                    d.getDecidedBy(), d.getDecidedAt(), d.getComment()))
                            .toList();
                    return new ApprovalRequestStepDto(s.getId(), s.getUid(), s.getSequence(),
                            s.getApproverRoleCode(), s.getStatus(),
                            s.getResolvedBy(), s.getResolvedAt(), decisionDtos);
                })
                .toList();
        return new ApprovalRequestDto(
                r.getId(), r.getUid(), r.getCompanyId(), r.getBranchId(),
                r.getRequestNumber(), r.getDocumentType(), r.getDocumentUid(),
                r.getAmount(), r.getCurrency(), r.getStatus(), r.isAutoApproved(),
                r.getSourcePolicyId(), r.getSourcePolicyUid(), r.getSummary(),
                r.getSubmittedBy(), r.getSubmittedAt(), r.getResolvedAt(), r.getResolvedBy(),
                stepDtos);
    }
}
