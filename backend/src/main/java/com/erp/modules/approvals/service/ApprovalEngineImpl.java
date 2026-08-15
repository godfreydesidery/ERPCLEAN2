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
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.RoleRepository;
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
    private final AppUserRepository             userRepo;
    private final RoleRepository                roleRepo;
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
                              AppUserRepository userRepo,
                              RoleRepository roleRepo,
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
        this.userRepo       = userRepo;
        this.roleRepo       = roleRepo;
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

        // Idempotency check — return existing PENDING request; reject re-submit of a terminal one
        // (BR-APR-08 + OQ-APR-06: "blocked — the consumer raises a new document (new uid)").
        // Finding 5 fix: returning a terminal request as if it were active would let a consumer's
        // workflow believe the old rejection is still in-progress.
        Optional<ApprovalRequest> existing = requestRepo.findByCompanyIdAndDocumentTypeAndDocumentUid(
                req.companyId(), req.documentType(), req.documentUid());
        if (existing.isPresent()) {
            ApprovalRequest existingReq = existing.get();
            if (!existingReq.getStatus().isTerminal()) {
                log.debug("submitForApproval idempotency hit: returning PENDING request {} for ({}, {})",
                          existingReq.getUid(), req.documentType(), req.documentUid());
                return toDto(existingReq);
            }
            // req.documentUid() and internal code OQ-APR-06 intentionally not surfaced (error-hygiene rule)
            throw new com.erp.platform.common.api.ConflictException(
                    "This document has already been resolved with status " + existingReq.getStatus()
                    + ". To re-submit, create a new version of the document.");
        }

        Long branchId = branchRepo.findByUid(req.branchUid())
                .orElseThrow(() -> com.erp.platform.common.api.NotFoundException.of(
                        "Branch", req.branchUid()))
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
                                    d.getDecidedBy(), resolveUserName(d.getDecidedBy()),
                                    d.getDecidedAt(), d.getComment()))
                            .toList();
                    return new ApprovalRequestStepDto(s.getId(), s.getUid(), s.getSequence(),
                            s.getApproverRoleCode(), resolveRoleName(s.getApproverRoleCode()),
                            s.getStatus(), s.getResolvedBy(), s.getResolvedAt(), decisionDtos);
                })
                .toList();

        Branch branch = r.getBranchId() != null ? branchRepo.findById(r.getBranchId()).orElse(null) : null;

        return new ApprovalRequestDto(
                r.getId(), r.getUid(), r.getCompanyId(), r.getBranchId(),
                branch != null ? branch.getName() : null,
                branch != null ? branch.getCode() : null,
                r.getRequestNumber(), r.getDocumentType(), r.getDocumentUid(),
                r.getAmount(), r.getCurrency().value(), r.getStatus(),
                r.getCurrentStepSequence(), r.isAutoApproved(),
                r.getSourcePolicyId(), r.getSourcePolicyUid(), r.getSummary(),
                r.getSubmittedBy(), resolveUserName(r.getSubmittedBy()), r.getSubmittedAt(),
                r.getResolvedAt(), r.getResolvedBy(), resolveUserName(r.getResolvedBy()),
                stepDtos);
    }

    // ------------------------------------------------------------------
    // Read-time name resolution (managers' inbox complaint: raw ids/codes, no names) — a missing
    // user/branch/role row is not an error here, it simply yields null; the read must never fail.
    // ------------------------------------------------------------------

    /**
     * Resolves an {@code app_user} id to a display name (falls back to username when the display
     * name is blank). Returns {@code null} when the id is {@code null} or the user no longer exists.
     *
     * <p>NOTE — N+1: called once per submittedBy/resolvedBy per request. Acceptable for the current
     * inbox page size (matches the module's existing per-row resolution style, e.g.
     * {@code SalesOrderServiceImpl.buildDto}); revisit with a batch lookup if inbox pages grow large.
     */
    private String resolveUserName(Long userId) {
        if (userId == null) {
            return null;
        }
        return userRepo.findById(userId).map(this::displayNameOrUsername).orElse(null);
    }

    private String displayNameOrUsername(AppUser user) {
        String displayName = user.getDisplayName();
        return (displayName != null && !displayName.isBlank()) ? displayName : user.getUsername();
    }

    /**
     * Resolves an approver role code (frozen on the step at submit time) to the role's friendly
     * display name. Returns {@code null} when the code is {@code null} or no longer matches a role
     * (e.g. the role was deleted after the step was snapshotted).
     */
    private String resolveRoleName(String approverRoleCode) {
        if (approverRoleCode == null) {
            return null;
        }
        // P4-1c (ADR-0062). This was a derived findByCode returning Optional. Once V102 allows two
        // tenants to share a role code, that call throws IncorrectResultSizeDataAccessException on
        // the second row — an HTTP 500 in the approval inbox. Scoped to the caller's tenant, global
        // roles included, and tenant-first so a tenant's own role wins over a global namesake.
        RequestContext.Principal caller = RequestContext.get();
        return roleRepo.findVisibleByCode(
                        approverRoleCode, caller == null ? null : caller.organisationId())
                .stream().findFirst().map(Role::getName).orElse(null);
    }
}
