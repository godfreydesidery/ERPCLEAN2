package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.UserBranch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface UserBranchRepository extends JpaRepository<UserBranch, Long> {

    Optional<UserBranch> findByUid(String uid);

    /** All branch assignments for a user, ordered for display and fallback resolution (D-D). */
    List<UserBranch> findByUserIdOrderByAssignedAtAscIdAsc(Long userId);

    Optional<UserBranch> findByUserIdAndBranchId(Long userId, Long branchId);

    /** The user's current default assignment, if one is set (BR-1, ADR-0001 D-B). */
    Optional<UserBranch> findByUserIdAndIsDefaultTrue(Long userId);

    /**
     * Earliest remaining assignment for {@code userId} excluding the row with {@code excludeId}.
     * Used by the D-D auto-fallback: when the current default is removed, this finds the
     * next-earliest to promote (ORDER BY assigned_at ASC, id ASC for a deterministic tiebreak).
     */
    Optional<UserBranch> findFirstByUserIdAndIdNotOrderByAssignedAtAscIdAsc(Long userId, Long excludeId);

    /**
     * Assignments pointing at {@code branchId} that are currently a user's default. When a branch is
     * archived these defaults are cleared (and the users fall back) so a decommissioned branch can no
     * longer scope anyone's session (security review, Slice 4).
     */
    List<UserBranch> findByBranchIdAndIsDefaultTrue(Long branchId);

    /**
     * Returns {@code true} iff {@code userId} has at least one branch assignment in a branch that
     * belongs to {@code companyId}. Used to scope the internal-agent user check to the agent's own
     * company (security fix, finding 4).
     */
    @org.springframework.data.jpa.repository.Query("""
            SELECT COUNT(ub) > 0
            FROM UserBranch ub
            WHERE ub.userId = :userId
              AND ub.branch.company.id = :companyId
            """)
    boolean existsByUserIdAndBranchCompanyId(Long userId, Long companyId);
}
