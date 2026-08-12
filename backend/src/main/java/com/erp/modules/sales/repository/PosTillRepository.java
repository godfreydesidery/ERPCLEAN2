package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PosTill;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PosTillRepository extends JpaRepository<PosTill, Long> {

    Optional<PosTill> findByUid(String uid);

    @Query("SELECT t.companyId FROM PosTill t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<PosTill> findByCompanyIdAndBranchId(Long companyId, Long branchId);

    List<PosTill> findByCompanyId(Long companyId);

    /**
     * Company-scoped batch id lookup — used to label a page of POS sessions with their till NAMES in
     * one query instead of one read per row (UAT, 2026-08).
     *
     * <p>Company-scoped rather than a bare {@code findAllById} for the same reason
     * {@code TenantScopingRulesTest} bans bare {@code findById} in services: the result becomes a
     * user-visible label, so filtering in the database means a stray id can never surface another
     * tenant's till name. An empty collection returns no rows.
     */
    List<PosTill> findByCompanyIdAndIdIn(Long companyId, Collection<Long> ids);
}
