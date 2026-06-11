package com.erp.modules.approvals.service;

import com.erp.modules.approvals.domain.entity.ApprovalPolicy;
import com.erp.modules.approvals.domain.enums.PolicyBranchScope;
import com.erp.modules.approvals.repository.ApprovalPolicyRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The deterministic policy-match function (ADR-0022 D-3, BR-APR-01).
 *
 * <p>Given a submission {@code (companyId, documentType, amount, branchId)}, returns the ONE active
 * policy whose band contains the amount, preferring BRANCH-scoped over COMPANY_WIDE. At most one
 * policy matches (bands are non-overlapping by construction — BR-APR-02). Empty = no match →
 * the caller auto-approves (BR-APR-09, OQ-APR-01 default).
 */
@Component
public class ApprovalPolicyMatcher {

    private final ApprovalPolicyRepository policies;

    public ApprovalPolicyMatcher(ApprovalPolicyRepository policies) {
        this.policies = policies;
    }

    /**
     * Match function. Returns at most one policy. Prefer BRANCH-scoped; fall back to COMPANY_WIDE.
     * Within equal specificity, the band non-overlap invariant (BR-APR-02) ensures at most one.
     *
     * @param companyId    the submission's company
     * @param documentType the consumer-owned routing key
     * @param amount       the submitted amount (base currency)
     * @param branchId     the originating branch id (for branch-scope preference)
     * @return the matched policy, or empty for auto-approve
     */
    @Transactional(readOnly = true)
    public Optional<ApprovalPolicy> match(Long companyId,
                                          String documentType,
                                          BigDecimal amount,
                                          Long branchId) {
        List<ApprovalPolicy> candidates = policies.findMatchCandidates(companyId, documentType, amount);
        if (candidates.isEmpty()) {
            return Optional.empty();
        }

        // Prefer BRANCH-scoped policies whose branch_id matches exactly
        Optional<ApprovalPolicy> branchMatch = candidates.stream()
                .filter(p -> p.getBranchScope() == PolicyBranchScope.BRANCH
                             && p.getBranchId() != null
                             && p.getBranchId().equals(branchId))
                .findFirst();
        if (branchMatch.isPresent()) {
            return branchMatch;
        }

        // Fall back to COMPANY_WIDE
        return candidates.stream()
                .filter(p -> p.getBranchScope() == PolicyBranchScope.COMPANY_WIDE)
                .findFirst();
    }
}
