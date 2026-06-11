package com.erp.modules.fixedassets.repository;

import com.erp.modules.fixedassets.domain.entity.AssetDisposal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetDisposalRepository extends JpaRepository<AssetDisposal, Long> {

    Optional<AssetDisposal> findByUid(String uid);

    Optional<AssetDisposal> findByFixedAssetId(Long fixedAssetId);

    List<AssetDisposal> findByCompanyId(Long companyId);

    @Query("SELECT ad.companyId FROM AssetDisposal ad WHERE ad.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
