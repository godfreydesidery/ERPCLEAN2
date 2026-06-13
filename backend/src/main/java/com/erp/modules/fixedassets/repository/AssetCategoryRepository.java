package com.erp.modules.fixedassets.repository;

import com.erp.modules.fixedassets.domain.entity.AssetCategory;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AssetCategoryRepository extends JpaRepository<AssetCategory, Long> {

    Optional<AssetCategory> findByUid(String uid);

    Optional<AssetCategory> findByCompanyIdAndCode(Long companyId, String code);

    List<AssetCategory> findByCompanyId(Long companyId);

    Page<AssetCategory> findByCompanyId(Long companyId, Pageable pageable);

    @Query("SELECT ac.companyId FROM AssetCategory ac WHERE ac.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
