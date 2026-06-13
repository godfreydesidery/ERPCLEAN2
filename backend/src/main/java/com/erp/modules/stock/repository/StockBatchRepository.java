package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.StockBatch;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link StockBatch} (ADR-0028 D-7/D-10).
 */
public interface StockBatchRepository extends JpaRepository<StockBatch, Long> {

    Optional<StockBatch> findByUid(String uid);

    /** Lookup by (company, location, product, lotNumber) — the natural key. */
    Optional<StockBatch> findByCompanyIdAndBranchIdAndLocationIdAndProductIdAndLotNumber(
            Long companyId, Long branchId, Long locationId, Long productId, String lotNumber);

    /**
     * FEFO consumption order: lots for a (location, product) ordered by expiry_date ASC NULLS LAST, id ASC.
     * Only returns lots with qty_on_hand > 0 for FEFO consume.
     */
    @Query("""
            SELECT sb FROM StockBatch sb
            WHERE sb.companyId = :companyId
              AND sb.locationId = :locationId
              AND sb.productId = :productId
              AND sb.qtyOnHand > 0
            ORDER BY sb.expiryDate ASC NULLS LAST, sb.id ASC
            """)
    List<StockBatch> findForFefoConsume(
            @Param("companyId") Long companyId,
            @Param("locationId") Long locationId,
            @Param("productId") Long productId);

    /**
     * Expiry report: lots expiring on or before the horizon date with qty > 0.
     * Ordered by expiry_date ASC.
     */
    @Query("""
            SELECT sb FROM StockBatch sb
            WHERE sb.companyId = :companyId
              AND sb.expiryDate IS NOT NULL
              AND sb.expiryDate <= :horizon
              AND sb.qtyOnHand > 0
            ORDER BY sb.expiryDate ASC
            """)
    Page<StockBatch> findExpiring(
            @Param("companyId") Long companyId,
            @Param("horizon") LocalDate horizon,
            Pageable pageable);

    /** ScopeGuard resolution (D-10). */
    @Query("SELECT sb.companyId FROM StockBatch sb WHERE sb.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
