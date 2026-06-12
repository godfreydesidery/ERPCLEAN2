package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.StockSerial;
import com.erp.modules.stock.domain.enums.SerialStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link StockSerial} (ADR-0028 D-7/D-10).
 */
public interface StockSerialRepository extends JpaRepository<StockSerial, Long> {

    Optional<StockSerial> findByUid(String uid);

    /** Lookup by serial number within a company-product scope (uniqueness enforced by DB). */
    Optional<StockSerial> findByCompanyIdAndProductIdAndSerialNumber(
            Long companyId, Long productId, String serialNumber);

    /** Paged serial list by location and product (IN_STOCK filter common). */
    Page<StockSerial> findByCompanyIdAndLocationIdAndProductIdAndSerialStatus(
            Long companyId, Long locationId, Long productId, SerialStatus status, Pageable pageable);

    /** Paged serial lookup by product (all statuses — for the lookup/history view FR-INVD-27). */
    Page<StockSerial> findByCompanyIdAndProductId(Long companyId, Long productId, Pageable pageable);

    /** Count of IN_STOCK serials at a location for integrity invariant check (BR-INVD-11). */
    @Query("""
            SELECT COUNT(ss)
            FROM StockSerial ss
            WHERE ss.companyId = :companyId
              AND ss.locationId = :locationId
              AND ss.productId = :productId
              AND ss.serialStatus = 'IN_STOCK'
            """)
    long countInStockAtLocation(
            @Param("companyId") Long companyId,
            @Param("locationId") Long locationId,
            @Param("productId") Long productId);

    /** ScopeGuard resolution (D-10). */
    @Query("SELECT ss.companyId FROM StockSerial ss WHERE ss.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
