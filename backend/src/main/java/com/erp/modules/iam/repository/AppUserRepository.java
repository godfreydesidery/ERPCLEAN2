package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {

    Optional<AppUser> findByUid(String uid);

    Optional<AppUser> findByUsername(String username);

    boolean existsByUsername(String username);

    List<AppUser> findAllByOrderByUsername();

    /**
     * Company-scoped user list (tenant-isolation fix, security audit 2026-06-25; extended V77).
     * Returns every user who has membership in {@code companyId} via an active role grant OR a
     * branch assignment in a branch of that company OR an explicit active user_company row (V77
     * additive oracle). Ordered alphabetically.
     */
    @Query("""
            SELECT DISTINCT u FROM AppUser u
            WHERE EXISTS (
                      SELECT 1 FROM UserRole ur
                      WHERE ur.userId = u.id
                        AND ur.revokedAt IS NULL
                        AND ur.companyId = :companyId)
               OR EXISTS (
                      SELECT 1 FROM UserBranch ub
                      WHERE ub.userId = u.id
                        AND ub.branch.company.id = :companyId)
               OR EXISTS (
                      SELECT 1 FROM UserCompany uc
                      WHERE uc.userId = u.id
                        AND uc.company.id = :companyId
                        AND uc.revokedAt IS NULL)
            ORDER BY u.username
            """)
    List<AppUser> findAllInCompanyOrderByUsername(@Param("companyId") Long companyId);

    /**
     * Membership check for the {@code getByUid} tenant-isolation guard. Returns {@code true} iff
     * {@code userId} belongs to {@code companyId} via an active role grant OR a branch assignment
     * OR an explicit active user_company row (V77 additive oracle).
     */
    @Query("""
            SELECT COUNT(u) > 0 FROM AppUser u
            WHERE u.id = :userId
              AND (EXISTS (
                      SELECT 1 FROM UserRole ur
                      WHERE ur.userId = u.id
                        AND ur.revokedAt IS NULL
                        AND ur.companyId = :companyId)
                   OR EXISTS (
                      SELECT 1 FROM UserBranch ub
                      WHERE ub.userId = u.id
                        AND ub.branch.company.id = :companyId)
                   OR EXISTS (
                      SELECT 1 FROM UserCompany uc
                      WHERE uc.userId = u.id
                        AND uc.company.id = :companyId
                        AND uc.revokedAt IS NULL))
            """)
    boolean existsUserInCompany(@Param("userId") Long userId, @Param("companyId") Long companyId);

    /**
     * F9 (ADR-0004 D-8): single indexed PK + status check used by the filter to re-validate the
     * user is still active on every authenticated request.
     */
    boolean existsByIdAndStatus(Long id, MasterStatus status);

    /**
     * Whether a user is active AND NOT the super-admin. Used to forbid binding the root user as an
     * internal sales agent (BR-PARTY-10): root is a system super-user, not a salesperson.
     */
    boolean existsByIdAndStatusAndRootFalse(Long id, MasterStatus status);
}
