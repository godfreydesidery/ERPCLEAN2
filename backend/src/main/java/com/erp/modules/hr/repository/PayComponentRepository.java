package com.erp.modules.hr.repository;

import com.erp.modules.hr.domain.entity.PayComponent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface PayComponentRepository extends JpaRepository<PayComponent, Long> {

    Optional<PayComponent> findByUid(String uid);

    @Query("SELECT p.companyId FROM PayComponent p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(String uid);

    List<PayComponent> findByCompanyIdAndActiveTrue(Long companyId);

    List<PayComponent> findByCompanyId(Long companyId);

    boolean existsByCompanyIdAndCode(Long companyId, String code);
}
