package com.erp.modules.approvals.service;

import com.erp.modules.approvals.domain.dto.ApprovalPolicyDto;
import com.erp.modules.approvals.domain.dto.ApprovalPolicyStepDto;
import com.erp.modules.approvals.domain.dto.CreateApprovalPolicyRequest;
import com.erp.modules.approvals.domain.dto.PolicyStepInputDto;
import com.erp.modules.approvals.domain.dto.UpdateApprovalPolicyRequest;
import com.erp.modules.approvals.domain.entity.ApprovalPolicy;
import com.erp.modules.approvals.domain.entity.ApprovalPolicyStep;
import com.erp.modules.approvals.domain.enums.PolicyBranchScope;
import com.erp.modules.approvals.repository.ApprovalPolicyRepository;
import com.erp.modules.approvals.repository.ApprovalPolicyStepRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Approval policy master CRUD (ADR-0022 D-3, FR-APR-01/02).
 */
@Service
@Transactional
public class ApprovalPolicyServiceImpl implements ApprovalPolicyService {

    private static final String AUDIT_TARGET = "approval_policies";

    private final ApprovalPolicyRepository     policyRepo;
    private final ApprovalPolicyStepRepository stepRepo;
    private final BranchRepository             branchRepo;
    private final RoleRepository               roleRepo;
    private final ScopeGuard                   scopeGuard;
    private final AuditService                 audit;

    public ApprovalPolicyServiceImpl(ApprovalPolicyRepository policyRepo,
                                     ApprovalPolicyStepRepository stepRepo,
                                     BranchRepository branchRepo,
                                     RoleRepository roleRepo,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.policyRepo = policyRepo;
        this.stepRepo   = stepRepo;
        this.branchRepo = branchRepo;
        this.roleRepo   = roleRepo;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public ApprovalPolicyDto create(CreateApprovalPolicyRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, req.companyId());

        Long branchId = resolveBranchId(req.branchScope(), req.branchUid(), req.companyId());
        validateSteps(req.steps());
        BigDecimal effectiveMax = effectiveMax(req.maxAmount());
        validateNonOverlap(req.companyId(), req.documentType(), req.branchScope(), branchId,
                           req.minAmount(), effectiveMax, null);

        ApprovalPolicy policy = new ApprovalPolicy(
                req.companyId(), req.documentType(), req.name(),
                req.branchScope(), branchId, req.minAmount(), req.maxAmount(),
                "TZS", req.notes(), principal != null ? principal.userId() : null);
        policyRepo.save(policy);

        saveSteps(policy, req.steps(), principal);

        audit.record(AuditEvent.of(AuditActions.APPROVAL_POLICY_CREATE, AUDIT_TARGET,
                policy.getId(), policy.getUid()));
        return toDto(policy);
    }

    @Override
    public ApprovalPolicyDto update(String uid, UpdateApprovalPolicyRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        ApprovalPolicy policy = policyRepo.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Approval policy not found."));
        scopeGuard.assertCanActIn(principal, policy.getCompanyId());

        Long branchId = resolveBranchId(req.branchScope(), req.branchUid(), policy.getCompanyId());
        validateSteps(req.steps());
        BigDecimal effectiveMax = effectiveMax(req.maxAmount());
        validateNonOverlap(policy.getCompanyId(), req.documentType(), req.branchScope(), branchId,
                           req.minAmount(), effectiveMax, policy.getId());

        policy.setDocumentType(req.documentType());
        policy.setName(req.name());
        policy.setBranchScope(req.branchScope());
        policy.setBranchId(branchId);
        policy.setMinAmount(req.minAmount());
        policy.setMaxAmount(req.maxAmount());
        policy.setNotes(req.notes());
        policy.setActive(req.active());
        policy.setUpdatedAt(Instant.now());
        policy.setUpdatedBy(principal != null ? principal.userId() : null);

        // Replace steps
        stepRepo.deleteByApprovalPolicyId(policy.getId());
        stepRepo.flush();
        saveSteps(policy, req.steps(), principal);

        audit.record(AuditEvent.of(AuditActions.APPROVAL_POLICY_UPDATE, AUDIT_TARGET,
                policy.getId(), policy.getUid()));
        return toDto(policy);
    }

    @Override
    public ApprovalPolicyDto deactivate(String uid) {
        RequestContext.Principal principal = RequestContext.get();
        ApprovalPolicy policy = policyRepo.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Approval policy not found."));
        scopeGuard.assertCanActIn(principal, policy.getCompanyId());

        policy.setActive(false);
        policy.setStatus(MasterStatus.ARCHIVED);
        policy.setUpdatedAt(Instant.now());
        policy.setUpdatedBy(principal != null ? principal.userId() : null);

        audit.record(AuditEvent.of(AuditActions.APPROVAL_POLICY_DEACTIVATE, AUDIT_TARGET,
                policy.getId(), policy.getUid()));
        return toDto(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public ApprovalPolicyDto getByUid(String uid) {
        RequestContext.Principal principal = RequestContext.get();
        ApprovalPolicy policy = policyRepo.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Approval policy not found."));
        scopeGuard.assertCanActIn(principal, policy.getCompanyId());
        return toDto(policy);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApprovalPolicyDto> listByCompany(Long companyId, Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        return policyRepo.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApprovalPolicyDto> listByCompanyAndDocumentType(Long companyId, String documentType,
                                                                 Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, companyId);
        return policyRepo.findByCompanyIdAndDocumentType(companyId, documentType, pageable)
                         .map(this::toDto);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Long resolveBranchId(PolicyBranchScope scope, String branchUid, Long companyId) {
        if (scope == PolicyBranchScope.BRANCH) {
            if (branchUid == null || branchUid.isBlank()) {
                throw new ConflictException("branchUid is required when branchScope = BRANCH");
            }
            // Use company-scoped finder: returns empty when the branch uid does not exist OR
            // belongs to a different company. Throw 404 in both cases to avoid confirming
            // cross-tenant existence (confused-deputy guard, SECURITY-FIX).
            return branchRepo.findByUidAndCompanyId(branchUid, companyId)
                    .orElseThrow(() -> new NotFoundException("Branch not found."))
                    .getId();
        }
        return null;
    }

    private void validateSteps(List<PolicyStepInputDto> steps) {
        if (steps == null || steps.isEmpty()) {
            throw new ConflictException("A policy must have at least one approval step.");
        }
        for (PolicyStepInputDto step : steps) {
            if (!roleRepo.existsByCode(step.approverRoleCode())) {
                throw new NotFoundException("Role code not found: " + step.approverRoleCode());
            }
        }
        // Validate dense sequences 1..N
        List<Integer> seqs = steps.stream().map(PolicyStepInputDto::sequence).sorted().toList();
        for (int i = 0; i < seqs.size(); i++) {
            if (seqs.get(i) != i + 1) {
                throw new ConflictException("Step sequence numbers must be consecutive starting from 1 with no gaps.");
            }
        }
    }

    private BigDecimal effectiveMax(BigDecimal maxAmount) {
        // For the overlap query, treat NULL max as a very large number
        return maxAmount != null ? maxAmount : new BigDecimal("999999999999999.9999");
    }

    private void validateNonOverlap(Long companyId, String documentType, PolicyBranchScope scope,
                                     Long branchId, BigDecimal minAmount, BigDecimal effectiveMax,
                                     Long excludeId) {
        List<ApprovalPolicy> overlaps = policyRepo.findOverlapping(
                companyId, documentType, scope, branchId,
                minAmount, effectiveMax, MasterStatus.ACTIVE, excludeId);
        if (!overlaps.isEmpty()) {
            // BR-APR-02: approval policy bands must not overlap for the same document type and branch scope.
            throw new ConflictException(
                    "The amount range you entered overlaps an existing active approval policy for this document type. "
                    + "Please adjust the minimum or maximum amount so the ranges do not overlap.");
        }
    }

    private void saveSteps(ApprovalPolicy policy, List<PolicyStepInputDto> steps,
                           RequestContext.Principal principal) {
        Long actorId = principal != null ? principal.userId() : null;
        for (PolicyStepInputDto s : steps) {
            ApprovalPolicyStep step = new ApprovalPolicyStep(
                    policy.getId(), policy.getCompanyId(),
                    s.sequence().shortValue(), s.approverRoleCode(), actorId);
            stepRepo.save(step);
        }
    }

    ApprovalPolicyDto toDto(ApprovalPolicy policy) {
        List<ApprovalPolicyStepDto> stepDtos = stepRepo
                .findByApprovalPolicyIdOrderBySequence(policy.getId())
                .stream()
                .map(s -> new ApprovalPolicyStepDto(s.getId(), s.getUid(),
                                                     s.getSequence(), s.getApproverRoleCode()))
                .toList();
        return new ApprovalPolicyDto(
                policy.getId(), policy.getUid(), policy.getCompanyId(),
                policy.getDocumentType(), policy.getName(), policy.getBranchScope(),
                policy.getBranchId(), policy.getMinAmount(), policy.getMaxAmount(),
                policy.getCurrency().value(), policy.getEffectiveFrom(), policy.getEffectiveTo(),
                policy.isActive(), policy.getStatus(),
                policy.getNotes(), stepDtos);
    }
}
