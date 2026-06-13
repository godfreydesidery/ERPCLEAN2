package com.erp.modules.fixedassets.repository;

import com.erp.modules.fixedassets.domain.entity.FixedAsset;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface FixedAssetRepository extends JpaRepository<FixedAsset, Long> {

    Optional<FixedAsset> findByUid(String uid);

    Optional<FixedAsset> findByCompanyIdAndAssetNumber(Long companyId, String assetNumber);

    Page<FixedAsset> findByCompanyId(Long companyId, Pageable pageable);

    Page<FixedAsset> findByCompanyIdAndStatus(Long companyId, FixedAssetStatus status, Pageable pageable);

    List<FixedAsset> findByCompanyIdAndStatus(Long companyId, FixedAssetStatus status);

    @Query("SELECT fa.companyId FROM FixedAsset fa WHERE fa.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    @Query("""
            SELECT COALESCE(SUM(fa.carryingCost), 0)
            FROM FixedAsset fa
            WHERE fa.companyId = :companyId AND fa.status = 'IN_SERVICE'
            """)
    java.math.BigDecimal sumCarryingCostInService(@Param("companyId") Long companyId);

    @Query("""
            SELECT COALESCE(SUM(fa.accumulatedDepreciation), 0)
            FROM FixedAsset fa
            WHERE fa.companyId = :companyId AND fa.status = 'IN_SERVICE'
            """)
    java.math.BigDecimal sumAccumulatedDepreciationInService(@Param("companyId") Long companyId);
}
