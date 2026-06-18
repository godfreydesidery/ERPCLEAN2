package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.PromotionUsage;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

/**
 * Append-only redemption log for promotions (P2 D5, ADR-0041 D5).
 *
 * <p>{@link #countByPromotionId(Long)} backs {@code usage_limit} enforcement — the count of
 * existing redemptions for a promotion is compared against its limit before a new redemption is
 * recorded.
 */
public interface PromotionUsageRepository extends JpaRepository<PromotionUsage, Long> {

    Optional<PromotionUsage> findByUid(String uid);

    /** Total redemptions recorded for a promotion — used for usage_limit enforcement. */
    long countByPromotionId(Long promotionId);

    List<PromotionUsage> findByPromotionId(Long promotionId);
}
