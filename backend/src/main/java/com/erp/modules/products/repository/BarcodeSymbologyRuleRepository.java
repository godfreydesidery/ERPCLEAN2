package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.BarcodeSymbologyRule;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BarcodeSymbologyRuleRepository extends JpaRepository<BarcodeSymbologyRule, Long> {

    Optional<BarcodeSymbologyRule> findByUid(String uid);

    Optional<BarcodeSymbologyRule> findByCompanyIdAndCode(Long companyId, String code);

    /**
     * Returns all ACTIVE rules for a company, ordered by priority ascending.
     * Used by the decode hot path: the first matching rule wins.
     */
    List<BarcodeSymbologyRule> findByCompanyIdAndStatusOrderByPriorityAsc(Long companyId,
                                                                          MasterStatus status);

    /**
     * Resolves a symbology rule uid to its owning company id.
     * Required by {@code ScopeGuard.companyIdOf("symbologyrule", uid)}.
     */
    @Query("SELECT r.companyId FROM BarcodeSymbologyRule r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
