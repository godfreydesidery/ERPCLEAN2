package com.erp.modules.crm.repository;

import com.erp.modules.crm.domain.entity.Lead;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface LeadRepository extends JpaRepository<Lead, Long> {

    Optional<Lead> findByUid(String uid);

    @Query("SELECT l.companyId FROM Lead l WHERE l.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<Lead> findByCompanyIdAndBranchId(Long companyId, Long branchId, Pageable pageable);

    Page<Lead> findByCompanyId(Long companyId, Pageable pageable);

    boolean existsByCompanyIdAndLeadNumber(Long companyId, String leadNumber);
}
