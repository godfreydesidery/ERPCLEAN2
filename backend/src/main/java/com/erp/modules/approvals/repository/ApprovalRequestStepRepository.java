package com.erp.modules.approvals.repository;

import com.erp.modules.approvals.domain.entity.ApprovalRequestStep;
import com.erp.modules.approvals.domain.enums.ApprovalStepStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalRequestStepRepository extends JpaRepository<ApprovalRequestStep, Long> {

    Optional<ApprovalRequestStep> findByUid(String uid);

    List<ApprovalRequestStep> findByApprovalRequestIdOrderBySequence(Long approvalRequestId);

    /**
     * The current open step — the lowest-sequence PENDING step for a request (BR-APR-04, D-5,
     * ix_approval_request_steps_open index). At most one step per request can be PENDING and be the
     * lowest; the LIMIT 1 makes it a single-row fetch.
     */
    @Query("""
            SELECT s FROM ApprovalRequestStep s
            WHERE s.approvalRequestId = :requestId
              AND s.status = com.erp.modules.approvals.domain.enums.ApprovalStepStatus.PENDING
            ORDER BY s.sequence ASC
            """)
    List<ApprovalRequestStep> findPendingStepsOrdered(@Param("requestId") Long requestId);

    /**
     * Inbox join helper: PENDING request step ids for the given request and role code
     * (used by ApprovalInboxQuery to find requests the caller can decide).
     */
    @Query("""
            SELECT s FROM ApprovalRequestStep s
            WHERE s.approvalRequestId = :requestId
              AND s.status = com.erp.modules.approvals.domain.enums.ApprovalStepStatus.PENDING
              AND s.approverRoleCode = :roleCode
            ORDER BY s.sequence ASC
            """)
    List<ApprovalRequestStep> findPendingStepsForRole(@Param("requestId") Long requestId,
                                                      @Param("roleCode") String roleCode);

    /** All steps with a given status for a request — used for bulk SKIPPED transition on reject/recall. */
    List<ApprovalRequestStep> findByApprovalRequestIdAndStatus(Long approvalRequestId,
                                                                ApprovalStepStatus status);

    /**
     * Atomically sets every PENDING step on a request to SKIPPED in a single DML statement
     * (Finding 1 fix — ADR-0022 D-6 "one reject kills the chain"). A single UPDATE is
     * atomic and eliminates the risk of intermediate inconsistent state from an entity loop.
     * {@code @Modifying} clears the 1st-level cache after execution so subsequent reads see
     * the updated status.
     */
    @Modifying(clearAutomatically = true)
    @Query("""
            UPDATE ApprovalRequestStep s
            SET s.status = com.erp.modules.approvals.domain.enums.ApprovalStepStatus.SKIPPED
            WHERE s.approvalRequestId = :requestId
              AND s.status = com.erp.modules.approvals.domain.enums.ApprovalStepStatus.PENDING
            """)
    void updatePendingToSkipped(@Param("requestId") Long requestId);

    /**
     * Inbox query: find request ids for PENDING requests where the current open step matches one of
     * the caller's role codes, in the caller's company, excluding requests submitted by the caller,
     * AND (for branch-scoped requests) the caller has a user_branch assignment for the request's
     * branch (Finding 2 fix — ADR-0022 D-8: "for a BRANCH-scoped request the user has access to
     * that branch via user_branch"). The LEFT JOIN + IS NOT NULL pattern mirrors canDecide().
     */
    @Query("""
            SELECT DISTINCT s.approvalRequestId FROM ApprovalRequestStep s
            JOIN ApprovalRequest r ON r.id = s.approvalRequestId
            LEFT JOIN com.erp.modules.iam.domain.entity.UserBranch ub
                   ON ub.userId = :callerId AND ub.branch.id = r.branchId
            WHERE s.status = com.erp.modules.approvals.domain.enums.ApprovalStepStatus.PENDING
              AND r.status = com.erp.modules.approvals.domain.enums.ApprovalRequestStatus.PENDING
              AND r.companyId = :companyId
              AND s.approverRoleCode IN :roleCodes
              AND r.submittedBy <> :callerId
              AND ub.userId IS NOT NULL
              AND s.sequence = (
                  SELECT MIN(s2.sequence) FROM ApprovalRequestStep s2
                  WHERE s2.approvalRequestId = s.approvalRequestId
                    AND s2.status = com.erp.modules.approvals.domain.enums.ApprovalStepStatus.PENDING
              )
            """)
    List<Long> findInboxRequestIds(@Param("companyId") Long companyId,
                                   @Param("roleCodes") List<String> roleCodes,
                                   @Param("callerId") Long callerId);
}
