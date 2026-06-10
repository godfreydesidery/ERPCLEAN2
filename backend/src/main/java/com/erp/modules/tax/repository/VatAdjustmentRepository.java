package com.erp.modules.tax.repository;

import com.erp.modules.tax.domain.entity.VatAdjustment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface VatAdjustmentRepository extends JpaRepository<VatAdjustment, Long> {

    Optional<VatAdjustment> findByUid(String uid);

    /** ScopeGuard projection (ADR-0017 D-11). */
    @Query("SELECT a.companyId FROM VatAdjustment a WHERE a.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<VatAdjustment> findByVatReturnId(Long vatReturnId);

    Page<VatAdjustment> findByCompanyIdAndVatReturnId(Long companyId, Long vatReturnId, Pageable pageable);
}
