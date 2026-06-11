package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.StockOnHand;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link StockOnHand}.
 *
 * <p>All tenant-scoped queries carry both {@code companyId} and {@code branchId} — the
 * §3.2 tenant predicate. No query bypasses the scope (BR-STOCK-07, NFR-STOCK-01).
 */
public interface StockOnHandRepository extends JpaRepository<StockOnHand, Long> {

    /**
     * The upsert-probe for the posting primitive (D-4): returns the existing on-hand row for
     * the (company, branch, product) triple, or empty if first-touch.
     */
    Optional<StockOnHand> findByCompanyIdAndBranchIdAndProductId(
            Long companyId, Long branchId, Long productId);

    /** uid-lookup for the reorder-level edit endpoint (D-11). */
    Optional<StockOnHand> findByUid(String uid);

    /** Paged list for the on-hand view at the active branch (FR-STOCK-11). */
    Page<StockOnHand> findByCompanyIdAndBranchId(Long companyId, Long branchId, Pageable pageable);

    /**
     * Single-column projection: the company owning a stock-on-hand row identified by uid.
     * Used by {@link com.erp.platform.security.ScopeGuard#companyIdOf} (D-10).
     */
    @Query("SELECT s.companyId FROM StockOnHand s WHERE s.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /**
     * Check whether ANY movement has been posted for the given (company, branch, product).
     * Used by the opening-balance endpoint to reject a second opening-balance (D-11).
     */
    @Query("SELECT COUNT(s) > 0 FROM StockOnHand s WHERE s.companyId = :companyId AND s.branchId = :branchId AND s.productId = :productId")
    boolean existsByCompanyIdAndBranchIdAndProductId(
            @Param("companyId") Long companyId,
            @Param("branchId") Long branchId,
            @Param("productId") Long productId);

    /** All on-hand rows for a product across all branches of a company (cross-branch overview). */
    List<StockOnHand> findByCompanyIdAndProductId(Long companyId, Long productId);

    /**
     * All on-hand rows for a company across all branches — used by the valuation report
     * to aggregate value per product (ADR-0020 D-6).
     */
    List<StockOnHand> findByCompanyId(Long companyId);
}
