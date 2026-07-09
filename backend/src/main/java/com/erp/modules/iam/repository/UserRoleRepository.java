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

    /**
     * Branch-agnostic effective permission codes for a user across a whole company — every active
     * grant in the company regardless of branch. Used by the {@code AuthorityCeiling} target-authority
     * check on {@code setPasswordByUid} (ADR-0059): an account takeover lands in the victim's default
     * branch, so the ceiling must consider the target's authority in ANY branch of the company, not a
     * single active scope.
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
            """)
    List<String> resolvePermissionCodesAnyBranch(@Param("userId") Long userId,
                                                 @Param("companyId") Long companyId);

    /**
     * {@code true} iff the user holds any active (non-revoked) role grant in the company. Used by the
     * ADR-0046 company-removal guard (block while access remains).
     */
    boolean existsByUserIdAndCompanyIdAndRevokedAtIsNull(Long userId, Long companyId);

    // --- notifications (ADR-0024 D-9): audience resolution outside a request ---

    /**
     * Distinct user IDs holding {@code permissionCode} in the given company, narrowed to
     * {@code branchId} when provided (branch-scoped types, ADR-0024 D-9). Used by
     * {@code AudienceResolver} running as SYSTEM (no {@code RequestContext}).
     * {@code branchId} may be {@code null} for company-wide audience resolution.
     */
    @Query("""
            SELECT DISTINCT ur.userId
            FROM UserRole ur
            JOIN ur.role r
            JOIN r.permissions p
            WHERE ur.revokedAt IS NULL
              AND r.status = com.erp.platform.common.domain.MasterStatus.ACTIVE
              AND ur.companyId = :companyId
              AND (:branchId IS NULL OR ur.branchId IS NULL OR ur.branchId = :branchId)
              AND p.code = :permissionCode
            """)
    List<Long> findUserIdsWithPermission(@Param("companyId") Long companyId,
                                         @Param("branchId") Long branchId,
                                         @Param("permissionCode") String permissionCode);

    /**
     * Branch-agnostic variant — all users holding the permission anywhere in the company,
     * regardless of which branch they are assigned to. Used for non-branch-scoped types.
     */
    @Query("""
            SELECT DISTINCT ur.userId
            FROM UserRole ur
            JOIN ur.role r
            JOIN r.permissions p
            WHERE ur.revokedAt IS NULL
              AND r.status = com.erp.platform.common.domain.MasterStatus.ACTIVE
              AND ur.companyId = :companyId
              AND p.code = :permissionCode
            """)
    List<Long> findUserIdsWithPermissionInCompany(@Param("companyId") Long companyId,
                                                  @Param("permissionCode") String permissionCode);

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
