package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.LandedCost;
import com.erp.modules.purchases.domain.enums.LandedCostStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LandedCostRepository extends JpaRepository<LandedCost, Long> {

    Optional<LandedCost> findByUid(String uid);

    Page<LandedCost> findByCompanyId(Long companyId, Pageable pageable);

    Page<LandedCost> findByCompanyIdAndStatus(Long companyId, LandedCostStatus status,
                                               Pageable pageable);

    List<LandedCost> findByCompanyIdAndStatus(Long companyId, LandedCostStatus status);

    /** ScopeGuard target-type projection (ADR-0027 D-10). */
    @Query("SELECT lc.companyId FROM LandedCost lc WHERE lc.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
