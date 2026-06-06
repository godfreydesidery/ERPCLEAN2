package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.UserRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserRoleRepository extends JpaRepository<UserRole, Long> {

    Optional<UserRole> findByUid(String uid);

    /**
     * The resolve-per-scope hot path (ADR-0001 D-E). Distinct permission codes effective for a user
     * in the active company + branch: every active assignment in that company whose scope is either
     * company-wide ({@code branch_id IS NULL}) or matches the active branch. A null active branch
     * still picks up company-wide grants.
     */
    @Query("""
            SELECT DISTINCT p.code
            FROM UserRole ur
            JOIN ur.role r
            JOIN r.permissions p
            WHERE ur.userId = :userId
              AND ur.revokedAt IS NULL
              AND r.status = com.erp.platform.common.domain.MasterStatus.ACTIVE
              AND ur.companyId = :companyId
              AND (ur.branchId IS NULL OR ur.branchId = :branchId)
            """)
    List<String> resolvePermissionCodes(@Param("userId") Long userId,
                                        @Param("companyId") Long companyId,
                                        @Param("branchId") Long branchId);

    /** Active assignments for a user (any scope) — for the user-admin grant list. */
    List<UserRole> findByUserIdAndRevokedAtIsNull(Long userId);

    /** Guard the company-wide duplicate case (branch_id NULL) the partial index can't (DATA-MODEL §1.8). */
    @Query("""
            SELECT COUNT(ur) > 0 FROM UserRole ur
            WHERE ur.userId = :userId AND ur.role.id = :roleId
              AND ur.companyId = :companyId
              AND ((:branchId IS NULL AND ur.branchId IS NULL) OR ur.branchId = :branchId)
              AND ur.revokedAt IS NULL
            """)
    boolean existsActiveGrant(@Param("userId") Long userId,
                              @Param("roleId") Long roleId,
                              @Param("companyId") Long companyId,
                              @Param("branchId") Long branchId);
}
