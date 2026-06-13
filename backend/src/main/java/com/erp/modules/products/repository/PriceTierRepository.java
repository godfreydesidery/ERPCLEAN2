package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.PriceTier;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PriceTierRepository extends JpaRepository<PriceTier, Long> {

    Optional<PriceTier> findByUid(String uid);

    @Query("SELECT t.companyId FROM PriceTier t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<PriceTier> findByCompanyIdAndProductIdAndPriceListId(
            Long companyId, Long productId, Long priceListId);

    /**
     * Find the tier with the greatest min_qty <= quantity (the matching tier for a line).
     * Returns the best-matching tier row, or empty if none applies.
     */
    @Query("""
            SELECT t FROM PriceTier t
            WHERE t.productId = :productId
              AND t.priceListId = :priceListId
              AND t.minQty <= :qty
              AND t.status = 'ACTIVE'
            ORDER BY t.minQty DESC
            LIMIT 1
            """)
    Optional<PriceTier> findBestTier(
            @Param("productId") Long productId,
            @Param("priceListId") Long priceListId,
            @Param("qty") BigDecimal qty);

    @Query("SELECT COUNT(t) > 0 FROM PriceTier t WHERE t.productId = :productId AND t.priceListId = :priceListId AND t.minQty = :minQty AND t.id <> :excludeId")
    boolean existsDuplicateBreak(
            @Param("productId") Long productId,
            @Param("priceListId") Long priceListId,
            @Param("minQty") BigDecimal minQty,
            @Param("excludeId") Long excludeId);
}
