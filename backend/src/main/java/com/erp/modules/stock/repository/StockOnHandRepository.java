package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.StockOnHand;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
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
     * @deprecated Use the location-aware overload (ADR-0028 D-3). This method throws
     *             IncorrectResultSizeDataAccessException when the product is stocked at more than
     *             one location in the branch; use {@link #findAllByCompanyIdAndBranchIdAndProductId}
     *             for read-only aggregation.
     */
    @Deprecated
    Optional<StockOnHand> findByCompanyIdAndBranchIdAndProductId(
            Long companyId, Long branchId, Long productId);

    /**
     * All on-hand rows for a (company, branch, product) triple across every location.
     * Use this — not the deprecated single-row finder — when aggregating quantity or weighted-average
     * cost across all locations in a branch (e.g. BOM cost roll-up, MANUFACTURING-024).
     */
    List<StockOnHand> findAllByCompanyIdAndBranchIdAndProductId(
            Long companyId, Long branchId, Long productId);

    /**
     * Location-aware upsert-probe (ADR-0028 D-3): returns the existing on-hand row for
     * (company, branch, location, product), or empty if first-touch.
     */
    Optional<StockOnHand> findByCompanyIdAndBranchIdAndLocationIdAndProductId(
            Long companyId, Long branchId, Long locationId, Long productId);

    /**
     * Same row as {@link #findByCompanyIdAndBranchIdAndLocationIdAndProductId}, taken under a
     * {@code SELECT … FOR UPDATE} row lock held to the end of the caller's transaction.
     *
     * <p>This is what makes the synchronous negative-stock decision safe. The on-hand quantity for a
     * sale is decremented <em>asynchronously</em> by the outbox poller roughly a second after the
     * invoice commits, so an unlocked read lets every till inside that window see the same stale
     * quantity and every one of them pass the check — eight back-to-back sales of 30 against 198 on
     * hand all succeeded and left −42. Serialising the reserve on this row closes the window:
     * the second transaction blocks here and re-reads the reservation the first one committed.
     *
     * <p>Reservations for a branch live on the branch default-location row (ADR-0028 D-3), so that
     * single row is the natural serialisation point for a (company, branch, product).
     *
     * <p><strong>Call it before any other read of the same row in the transaction.</strong> If the
     * entity is already managed, Hibernate returns the cached copy and the lock buys nothing.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT s FROM StockOnHand s
            WHERE s.companyId = :companyId
              AND s.branchId = :branchId
              AND s.locationId = :locationId
              AND s.productId = :productId
            """)
    Optional<StockOnHand> lockByCompanyBranchLocationProduct(
            @Param("companyId") Long companyId,
            @Param("branchId") Long branchId,
            @Param("locationId") Long locationId,
            @Param("productId") Long productId);

    /** uid-lookup for the reorder-level edit endpoint (D-11). */
    Optional<StockOnHand> findByUid(String uid);

    /** Paged list for the on-hand view at the active branch (FR-STOCK-11). */
    Page<StockOnHand> findByCompanyIdAndBranchId(Long companyId, Long branchId, Pageable pageable);

    /**
     * Paged on-hand view at the active branch, restricted to a set of product ids
     * (the on-hand search path, FR-STOCK-11). The product-id set is resolved upstream by a
     * company-scoped product code/name search (ProductService) — keeping the cross-module
     * boundary intact (no join to the products entity from the stock module).
     */
    Page<StockOnHand> findByCompanyIdAndBranchIdAndProductIdIn(
            Long companyId, Long branchId, List<Long> productIds, Pageable pageable);

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

    // --- notifications scanner (ADR-0024 D-7): low-stock scan ---

    /**
     * STK-10 / LBO-16: the reorder level a row is judged against — its own (set on Stock On-Hand),
     * else the product's level for the branch ({@code product_branch}), else the product's own
     * level (Product Master / product import). Before this only the first was read, so a level
     * typed into the Product Master never raised a flag, a report row or an alert.
     */
    String EFFECTIVE_REORDER_LEVEL = "COALESCE(s.reorder_level, pb.reorder_level, p.reorder_level)";

    /**
     * Joins for {@link #EFFECTIVE_REORDER_LEVEL}, plus the rule for an INHERITED level: it applies
     * to a row that holds stock or sits at the branch's default location. A zero row elsewhere is a
     * bookkeeping leftover (a received transfer leaves one at In-Transit) and must not be reported
     * as "out of stock" against the product's level.
     */
    String EFFECTIVE_REORDER_FROM = """
            FROM stock_on_hand s
            JOIN products p               ON p.id = s.product_id AND p.company_id = s.company_id
            LEFT JOIN product_branch pb   ON pb.product_id = s.product_id AND pb.branch_id = s.branch_id
            LEFT JOIN stock_locations l   ON l.id = s.location_id
            WHERE s.company_id = :companyId
              AND (s.reorder_level IS NOT NULL OR s.quantity <> 0 OR COALESCE(l.is_default, true))
              AND COALESCE(s.reorder_level, pb.reorder_level, p.reorder_level) IS NOT NULL
            """;

    /**
     * On-hand rows at or below their effective reorder level for a company.
     * Used by {@code NotificationScanner}.
     */
    @Query(value = "SELECT s.* " + EFFECTIVE_REORDER_FROM
            + " AND s.quantity <= " + EFFECTIVE_REORDER_LEVEL, nativeQuery = true)
    List<StockOnHand> findAtOrBelowReorderByCompany(@Param("companyId") Long companyId);

    /**
     * On-hand rows ABOVE their effective reorder level — re-arm sweep
     * (low-stock recovery scan, BR-NOTIF-08).
     */
    @Query(value = "SELECT s.* " + EFFECTIVE_REORDER_FROM
            + " AND s.quantity > " + EFFECTIVE_REORDER_LEVEL, nativeQuery = true)
    List<StockOnHand> findAboveReorderByCompany(@Param("companyId") Long companyId);

    /**
     * {@code [stock_on_hand.id, effective reorder level]} for the given rows of one company
     * (STK-10) — the level is null when none is set anywhere. Applicability (holds stock or default
     * location) is left to the caller.
     */
    @Query(value = """
            SELECT s.id, COALESCE(s.reorder_level, pb.reorder_level, p.reorder_level)
            FROM stock_on_hand s
            JOIN products p             ON p.id = s.product_id AND p.company_id = s.company_id
            LEFT JOIN product_branch pb ON pb.product_id = s.product_id AND pb.branch_id = s.branch_id
            WHERE s.company_id = :companyId
              AND s.id IN (:ids)
            """, nativeQuery = true)
    List<Object[]> findEffectiveReorderLevels(@Param("companyId") Long companyId,
                                              @Param("ids") java.util.Collection<Long> ids);
}
