package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.StandingOrder;
import com.erp.modules.sales.domain.enums.StandingStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface StandingOrderRepository extends JpaRepository<StandingOrder, Long> {

    Optional<StandingOrder> findByUid(String uid);

    @Query("SELECT s.companyId FROM StandingOrder s WHERE s.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<StandingOrder> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * All ACTIVE standing orders with next_run_date <= today — the scheduler batch query.
     * No company filter: processes all companies in one sweep.
     */
    @Query("""
            SELECT s FROM StandingOrder s
            WHERE s.status = 'ACTIVE'
              AND s.nextRunDate <= :today
              AND (s.endDate IS NULL OR s.endDate >= :today)
            """)
    List<StandingOrder> findDueForGeneration(@Param("today") LocalDate today);
}
