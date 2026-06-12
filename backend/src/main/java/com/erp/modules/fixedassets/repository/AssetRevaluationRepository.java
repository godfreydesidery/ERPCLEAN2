package com.erp.modules.fixedassets.repository;

import com.erp.modules.fixedassets.domain.entity.AssetRevaluation;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetRevaluationRepository extends JpaRepository<AssetRevaluation, Long> {

    Optional<AssetRevaluation> findByUid(String uid);

    List<AssetRevaluation> findByFixedAssetIdOrderByRevaluationDateAsc(Long fixedAssetId);

    List<AssetRevaluation> findByCompanyId(Long companyId);

    @Query("SELECT ar.companyId FROM AssetRevaluation ar WHERE ar.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
