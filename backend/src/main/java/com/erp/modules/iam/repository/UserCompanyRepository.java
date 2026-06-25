package com.erp.modules.iam.repository;

import com.erp.modules.iam.domain.entity.UserCompany;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface UserCompanyRepository extends JpaRepository<UserCompany, Long> {

    Optional<UserCompany> findByUid(String uid);

    /** {@code true} iff there is already an active membership for this (user, company) pair. */
    boolean existsByUserIdAndCompanyIdAndRevokedAtIsNull(Long userId, Long companyId);

    /**
     * Active (non-revoked) memberships for a given user, ordered by assignment time.
     * Used by the list-for-user endpoint.
     */
    List<UserCompany> findByUserIdAndRevokedAtIsNullOrderByAssignedAtAscIdAsc(Long userId);

    /**
     * Active memberships for a given company — used by scope checks / backfill.
     */
    List<UserCompany> findByCompanyIdAndRevokedAtIsNull(Long companyId);

    /**
     * Fetch a single active membership row for (user, company). Used by
     * {@code ensureMembership} to short-circuit if one already exists.
     */
    @Query("""
            SELECT uc FROM UserCompany uc
            WHERE uc.userId = :userId
              AND uc.company.id = :companyId
              AND uc.revokedAt IS NULL
            """)
    Optional<UserCompany> findActive(@Param("userId") Long userId,
                                     @Param("companyId") Long companyId);

    /**
     * Distinct company ids for which {@code userId} holds an active (non-revoked) membership.
     * Used by {@link com.erp.modules.iam.service.CompanyServiceImpl#listAccessibleByOrganisationUid}
     * to extend the additive oracle with explicit user_company membership.
     */
    @Query("""
            SELECT DISTINCT uc.company.id FROM UserCompany uc
            WHERE uc.userId = :userId
              AND uc.revokedAt IS NULL
            """)
    java.util.Set<Long> findActiveCompanyIdsByUserId(@Param("userId") Long userId);

    /**
     * Total row count used by the startup backfill guard: if {@code count() == 0} the table is
     * empty and the backfill should run; otherwise it is a no-op (already seeded on a previous boot).
     */
    @Override
    long count();
}
