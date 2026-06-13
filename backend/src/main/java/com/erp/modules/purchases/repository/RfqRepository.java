package com.erp.modules.purchases.repository;

import com.erp.modules.purchases.domain.entity.Rfq;
import com.erp.modules.purchases.domain.enums.RfqStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RfqRepository extends JpaRepository<Rfq, Long> {

    Optional<Rfq> findByUid(String uid);

    Page<Rfq> findByCompanyId(Long companyId, Pageable pageable);

    Page<Rfq> findByCompanyIdAndStatus(Long companyId, RfqStatus status, Pageable pageable);

    List<Rfq> findByCompanyIdAndStatus(Long companyId, RfqStatus status);

    boolean existsByCompanyIdAndRfqNumber(Long companyId, String rfqNumber);

    /** ScopeGuard target-type projection (ADR-0027 D-10). */
    @Query("SELECT r.companyId FROM Rfq r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
