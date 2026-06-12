package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.Promotion;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PromotionRepository extends JpaRepository<Promotion, Long> {

    Optional<Promotion> findByUid(String uid);

    @Query("SELECT p.companyId FROM Promotion p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<Promotion> findByCompanyId(Long companyId, Pageable pageable);

    /**
     * Find active promotions for a company matching (target=ALL or target=PRODUCT with
     * target_product_id, or target=CATEGORY matching the product's category) on businessDate,
     * ordered by priority DESC then uid ASC (deterministic tiebreak, OQ-SD-03).
     *
     * The caller filters target=CATEGORY by category string in Java after the query — cheaper than
     * a nullable string LIKE in SQL for this small result set.
     */
    @Query("""
            SELECT p FROM Promotion p
            WHERE p.companyId = :companyId
              AND p.status = 'ACTIVE'
              AND p.effectiveFrom <= :date
              AND p.effectiveTo >= :date
              AND (
                  p.target = 'ALL'
                  OR (p.target = 'PRODUCT' AND p.targetProductId = :productId)
                  OR p.target = 'CATEGORY'
              )
            ORDER BY p.priority DESC, p.uid ASC
            """)
    List<Promotion> findActiveForProduct(
            @Param("companyId") Long companyId,
            @Param("productId") Long productId,
            @Param("date") LocalDate date);
}
