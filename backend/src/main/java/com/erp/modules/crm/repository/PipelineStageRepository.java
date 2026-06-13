package com.erp.modules.crm.repository;

import com.erp.modules.crm.domain.entity.PipelineStage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PipelineStageRepository extends JpaRepository<PipelineStage, Long> {

    Optional<PipelineStage> findByUid(String uid);

    @Query("SELECT ps.companyId FROM PipelineStage ps WHERE ps.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<PipelineStage> findByCompanyIdOrderByDisplayOrder(Long companyId);

    boolean existsByCompanyIdAndName(Long companyId, String name);

    boolean existsByCompanyIdAndDisplayOrder(Long companyId, short displayOrder);
}
