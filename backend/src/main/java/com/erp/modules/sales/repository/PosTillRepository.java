package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PosTill;
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
}
