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
     * Company-scoped user list for ROOT callers (tenant-isolation fix, security audit 2026-06-25;
     * extended V77). Returns every user (including root) who has membership in {@code companyId}
     * via an active role grant OR a branch assignment in a branch of that company OR an explicit
     * active user_company row (V77 additive oracle). Ordered alphabetically.
     *
     * <p>Non-root callers must use {@link #findNonRootInCompanyOrderByUsername(Long)} instead.
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
     * Company-scoped user list for non-root callers. Identical membership predicate as
     * {@link #findAllInCompanyOrderByUsername(Long)} but excludes root users ({@code is_root =
     * false}) so the super-admin is never visible to ordinary principals. Ordered alphabetically.
     */
    @Query("""
            SELECT DISTINCT u FROM AppUser u
            WHERE u.root = false
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
            ORDER BY u.username
            """)
    List<AppUser> findNonRootInCompanyOrderByUsername(@Param("companyId") Long companyId);

    /**
     * Org-wide list of non-root users for the assign-existing-user picker when the caller is
     * non-root. Root users are intentionally excluded so they are never visible to ordinary
     * principals. Ordered alphabetically.
     */
    List<AppUser> findByRootFalseOrderByUsername();

    /**
     * P3-4 (ADR-0062): the org-wide user list, confined to one tenant. The unscoped
     * {@code findByRootFalseOrderByUsername} above returned every non-root user in the DATABASE —
     * on a shared instance that is a complete cross-tenant directory, and under the {@code @alias}
     * convention it hands over the tenant list too.
     *
     * <p>Strict equality, not NULL-tolerant: unlike roles, a user is never "global". A user with no
     * organisation is invisible here, which is the fail-closed direction.
     */
    List<AppUser> findByRootFalseAndOrganisationIdOrderByUsername(Long organisationId);

    /**
     * Every user of one tenant, root users included, plus any not yet attributed (ADR-0062 P3-8).
     *
     * <p>Backs the root-facing {@code UserServiceImpl.list()}, which previously called
     * {@link #findAllByOrderByUsername()} — every user in the database, of every customer.
     *
     * <p>NULL-tolerant on purpose. An account created before P2-1 stamped organisations, or through
     * a path that missed it, has a null organisation; a strict predicate would make it invisible to
     * the exact screen an administrator would use to notice and fix it. The population shrinks to
     * empty once the column is constrained NOT NULL.
     */
    @Query("SELECT u FROM AppUser u WHERE u.organisationId = :organisationId "
            + "OR u.organisationId IS NULL ORDER BY u.username")
    List<AppUser> findVisibleToOrganisationOrderByUsername(@Param("organisationId") Long organisationId);

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

    /**
     * Load a user by an id already trusted from a scoped parent row (e.g. a petty-cash fund's
     * {@code custodian_id}, ADR-0050 D-7 PR-B). Named finder (not the inherited {@code findById}),
     * mirroring {@code CompanyRepository.findScopedById} — the id here was already resolved and
     * company-verified at write time, never attacker-supplied at this call site, so this does not
     * trip the confused-deputy guard ({@code TenantScopingRulesTest}).
     */
    @Query("SELECT u FROM AppUser u WHERE u.id = :id")
    Optional<AppUser> findScopedById(@Param("id") Long id);

    /**
     * The per-request active-user check, returning the caller's tenant in the same lookup
     * (ADR-0062, P2-1). Replaces a bare {@code existsByIdAndStatus} in
     * {@code JwtRequestContextFilter}: the filter needs both answers on every request, and one PK
     * lookup answering both is cheaper than an exists() followed by a second read.
     *
     * <p>Returned as a projection rather than {@code Optional<Long>} deliberately. The organisation
     * is nullable, and an {@code Optional<Long>} of a null value is empty — indistinguishable from
     * "this user is not active". A projection is present whenever the row is, and carries a null
     * organisation as a null field, which is the distinction the filter has to make.
     */
    interface ActiveUserScope {
        Long getOrganisationId();

        /**
         * Root status as the DATABASE currently has it (ADR-0062 P3-13) - not as the token claimed
         * it fifteen minutes ago. Demoting a compromised root has to take effect on the next
         * request, not when their access token happens to expire, because the window you are trying
         * to close is exactly the incident during which you revoked them.
         */
        boolean getRoot();
    }

    @Query("SELECT u.organisationId AS organisationId, u.root AS root "
            + "FROM AppUser u WHERE u.id = :id AND u.status = :status")
    Optional<ActiveUserScope> findActiveScope(@Param("id") Long id, @Param("status") MasterStatus status);
}
