package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.CustomerPrice;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CustomerPriceRepository extends JpaRepository<CustomerPrice, Long> {

    Optional<CustomerPrice> findByUid(String uid);

    @Query("SELECT cp.companyId FROM CustomerPrice cp WHERE cp.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<CustomerPrice> findByCompanyIdAndCustomerId(Long companyId, Long customerId);

    /**
     * Find the active customer-specific price for (customer, product) valid on businessDate.
     * Status must be ACTIVE; window is inclusive on both ends (null = open-ended).
     */
    @Query("""
            SELECT cp FROM CustomerPrice cp
            WHERE cp.customerId = :customerId
              AND cp.productId = :productId
              AND cp.status = 'ACTIVE'
              AND (:date IS NULL OR cp.effectiveFrom IS NULL OR cp.effectiveFrom <= :date)
              AND (:date IS NULL OR cp.effectiveTo IS NULL OR cp.effectiveTo >= :date)
            ORDER BY cp.id ASC
            LIMIT 1
            """)
    Optional<CustomerPrice> findActiveForCustomerProduct(
            @Param("customerId") Long customerId,
            @Param("productId") Long productId,
            @Param("date") LocalDate date);
}
