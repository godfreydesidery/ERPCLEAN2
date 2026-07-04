package com.erp.modules.cashbank.repository;

import com.erp.modules.cashbank.domain.entity.PettyCashFund;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PettyCashFundRepository extends JpaRepository<PettyCashFund, Long> {

    Optional<PettyCashFund> findByUid(String uid);

    Optional<PettyCashFund> findByCompanyIdAndUid(Long companyId, String uid);

    /** ScopeGuard: resolve uid -> companyId (ADR-0050 D-7 PR-B). */
    @Query("SELECT f.companyId FROM PettyCashFund f WHERE f.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<PettyCashFund> findByCompanyIdOrderByNameAsc(Long companyId);

    /** Seeder idempotency guard (ADR-0050 D-7 PR-B, D-7.4/scope). */
    boolean existsByCompanyIdAndCode(Long companyId, String code);
}
