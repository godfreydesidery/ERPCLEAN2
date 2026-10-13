package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.ProductPrice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductPriceRepository extends JpaRepository<ProductPrice, Long> {

    List<ProductPrice> findByProductId(Long productId);

    /** All price rows on a price list within a company (rule-based mass price change). */
    List<ProductPrice> findByCompanyIdAndPriceListId(Long companyId, Long priceListId);

    /**
     * The base-unit price row for (product, price list) — unit_id IS NULL (ADR-0048 D-1).
     * Replaces the now-ambiguous {@code findByProductIdAndPriceListId} (a product can carry one
     * base row plus zero or more per-unit rows on the same price list).
     */
    Optional<ProductPrice> findByProductIdAndPriceListIdAndUnitIdIsNull(Long productId, Long priceListId);

    /** The per-unit price row for (product, price list, unit) — explicit pack-price override. */
    Optional<ProductPrice> findByProductIdAndPriceListIdAndUnitId(
            Long productId, Long priceListId, Long unitId);

    /**
     * The base-unit price row for a product, regardless of price list — used by the price-list-blind
     * resolver (ADR-0048 defers price-list-aware selection). A product may hold one base row PER
     * price list, so this can match several rows; {@code findFirst … OrderById} takes the lowest-id
     * row (deterministic, LIMIT 1) — restoring the pre-D-1 {@code findFirst()} tolerance instead of
     * throwing {@code IncorrectResultSizeDataAccessException} on a multi-price-list product.
     */
    Optional<ProductPrice> findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(Long productId);

    /** The per-unit price row for a product/unit, regardless of price list — first wins (see above). */
    Optional<ProductPrice> findFirstByProductIdAndUnitIdOrderByIdAsc(Long productId, Long unitId);

    /**
     * Every price row of a product inside one company, its price list (and unit) fetched with it,
     * oldest row first — the input the selling-price resolver chooses from (PRD-01).
     *
     * <p>Deliberately unfiltered by list status or validity dates: the resolver applies those rules
     * in one place ({@code SellingPriceRules}) so the walk-in, customer and legacy tiers all judge a
     * row the same way, and so {@code ProductServiceImpl.listPrices} can order the same rows by the
     * same rules. A product has a handful of rows (one per list, plus pack rows), so loading them
     * all is cheaper than one query per tier. {@code ORDER BY id} keeps the legacy "lowest id wins"
     * tie-break deterministic.
     */
    @Query("""
            SELECT pp FROM ProductPrice pp
              JOIN FETCH pp.priceList
              LEFT JOIN FETCH pp.unit
            WHERE pp.companyId = :companyId
              AND pp.product.id = :productId
            ORDER BY pp.id ASC
            """)
    List<ProductPrice> findPricingRowsOfProduct(@Param("companyId") Long companyId,
                                                @Param("productId") Long productId);

    /**
     * Whether this product carries ANY price row, on any price list, base or per-unit.
     *
     * <p>Used by {@code ProductServiceImpl.updateByUid} to refuse a base-unit change while prices
     * exist: a price amount is per unit, so the stored number silently changes meaning under a new
     * base unit, and the row also stops being reachable by the resolver.
     *
     * <p>Deliberately an existence check rather than {@code findByProductId(...).isEmpty()} — the
     * rows are never read, only counted, and a product on many price lists should not be loaded to
     * answer a yes/no. It is company-safe despite taking no companyId: the caller has already loaded
     * the product through a company-scoped finder, so the id cannot address another tenant's row.
     */
    boolean existsByProductId(Long productId);
}
