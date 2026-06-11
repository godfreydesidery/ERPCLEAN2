package com.erp.modules.approvals.repository;

import com.erp.modules.approvals.domain.entity.ApprovalPolicy;
import com.erp.modules.approvals.domain.enums.PolicyBranchScope;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalPolicyRepository extends JpaRepository<ApprovalPolicy, Long> {

    Optional<ApprovalPolicy> findByUid(String uid);

    /** ScopeGuard resolver (ADR-0022 D-11). */
    @Query("SELECT p.companyId FROM ApprovalPolicy p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<ApprovalPolicy> findByCompanyId(Long companyId, Pageable pageable);

    Page<ApprovalPolicy> findByCompanyIdAndDocumentType(Long companyId, String documentType, Pageable pageable);

    /**
     * Candidate set for the policy match function (ADR-0022 D-3, BR-APR-01).
     * Returns all active policies for the given company + documentType whose band contains the amount:
     * min_amount <= amount AND (max_amount IS NULL OR amount < max_amount).
     */
    @Query("""
            SELECT p FROM ApprovalPolicy p
            WHERE p.companyId = :companyId
              AND p.documentType = :documentType
              AND p.active = true
              AND p.status = com.erp.platform.common.domain.MasterStatus.ACTIVE
              AND p.minAmount <= :amount
              AND (p.maxAmount IS NULL OR :amount < p.maxAmount)
            """)
    List<ApprovalPolicy> findMatchCandidates(@Param("companyId") Long companyId,
                                             @Param("documentType") String documentType,
                                             @Param("amount") BigDecimal amount);

    /**
     * Non-overlap check for save-time band validation (BR-APR-02).
     * Returns policies that would overlap the half-open band [minAmount, maxAmount) for the same
     * (companyId, documentType, branchScope, branchId), excluding a given policy id (for edits).
     */
    @Query("""
            SELECT p FROM ApprovalPolicy p
            WHERE p.companyId = :companyId
              AND p.documentType = :documentType
              AND p.branchScope = :branchScope
              AND ((:branchId IS NULL AND p.branchId IS NULL) OR p.branchId = :branchId)
              AND p.active = true
              AND p.status = :status
              AND (:excludeId IS NULL OR p.id <> :excludeId)
              AND p.minAmount < :effectiveMax
              AND (p.maxAmount IS NULL OR p.maxAmount > :minAmount)
            """)
    List<ApprovalPolicy> findOverlapping(@Param("companyId") Long companyId,
                                         @Param("documentType") String documentType,
                                         @Param("branchScope") PolicyBranchScope branchScope,
                                         @Param("branchId") Long branchId,
                                         @Param("minAmount") BigDecimal minAmount,
                                         @Param("effectiveMax") BigDecimal effectiveMax,
                                         @Param("status") MasterStatus status,
                                         @Param("excludeId") Long excludeId);
}
