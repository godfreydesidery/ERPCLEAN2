package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.PurchaseRequisition;
import com.erp.modules.purchases.domain.enums.RequisitionStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PurchaseRequisitionRepository extends JpaRepository<PurchaseRequisition, Long> {

    Optional<PurchaseRequisition> findByUid(String uid);

    Page<PurchaseRequisition> findByCompanyId(Long companyId, Pageable pageable);

    Page<PurchaseRequisition> findByCompanyIdAndStatus(Long companyId, RequisitionStatus status,
                                                       Pageable pageable);

    List<PurchaseRequisition> findByCompanyIdAndStatus(Long companyId, RequisitionStatus status);

    boolean existsByCompanyIdAndRequisitionNumber(Long companyId, String requisitionNumber);

    /** ScopeGuard target-type projection (ADR-0027 D-10). */
    @Query("SELECT r.companyId FROM PurchaseRequisition r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
