package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.ProductPrice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPriceRepository extends JpaRepository<ProductPrice, Long> {

    List<ProductPrice> findByProductId(Long productId);

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
}
