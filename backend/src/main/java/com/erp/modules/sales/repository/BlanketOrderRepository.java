package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.BlanketOrder;
import com.erp.modules.sales.domain.enums.BlanketStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BlanketOrderRepository extends JpaRepository<BlanketOrder, Long> {

    Optional<BlanketOrder> findByUid(String uid);

    @Query("SELECT b.companyId FROM BlanketOrder b WHERE b.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<BlanketOrder> findByCompanyId(Long companyId, Pageable pageable);

    List<BlanketOrder> findByCompanyIdAndCustomerId(Long companyId, Long customerId);

    /**
     * Active blankets that are in-date for a customer — used by the drawdown service
     * to find eligible contracts.
     */
    @Query("""
            SELECT b FROM BlanketOrder b
            WHERE b.companyId = :companyId
              AND b.customerId = :customerId
              AND b.status = 'ACTIVE'
              AND b.validFrom <= :date
              AND b.validTo >= :date
            """)
    List<BlanketOrder> findActiveForCustomer(
            @Param("companyId") Long companyId,
            @Param("customerId") Long customerId,
            @Param("date") LocalDate date);
}
