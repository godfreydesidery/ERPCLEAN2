package com.erp.modules.tax.repository;

import com.erp.modules.tax.domain.entity.WhtType;
import com.erp.modules.tax.domain.enums.WhtKind;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WhtTypeRepository extends JpaRepository<WhtType, Long> {

    Optional<WhtType> findByUid(String uid);

    /** ScopeGuard projection (ADR-0017 D-11). */
    @Query("SELECT t.companyId FROM WhtType t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Optional<WhtType> findByCompanyIdAndUid(Long companyId, String uid);

    List<WhtType> findByCompanyId(Long companyId);

    List<WhtType> findByCompanyIdAndKindAndActiveTrue(Long companyId, WhtKind kind);

    boolean existsByCompanyIdAndCode(Long companyId, String code);
}
