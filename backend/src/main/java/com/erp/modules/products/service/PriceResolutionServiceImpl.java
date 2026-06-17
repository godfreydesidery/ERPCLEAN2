package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ResolvePriceRequest;
import com.erp.modules.products.domain.dto.ResolvedPriceDto;
import com.erp.modules.products.domain.entity.Promotion;
import com.erp.modules.products.domain.enums.PromotionEffect;
import com.erp.modules.products.domain.enums.PromotionTarget;
import com.erp.modules.products.repository.CustomerPriceRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.PriceTierRepository;
import com.erp.modules.products.repository.PromotionRepository;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic price resolver: customer price > promotion > tier > list > NONE (ADR-0029 D-6).
 * No stacking — first matching rule wins. Read-only transaction.
 */
@Service
@Transactional(readOnly = true)
public class PriceResolutionServiceImpl implements PriceResolutionService {

    private final CustomerPriceRepository customerPrices;
    private final PromotionRepository     promotions;
    private final PriceTierRepository     priceTiers;
    private final ProductPriceRepository  productPrices;
    private final ProductRepository       products;

    public PriceResolutionServiceImpl(CustomerPriceRepository customerPrices,
                                      PromotionRepository promotions,
                                      PriceTierRepository priceTiers,
                                      ProductPriceRepository productPrices,
                                      ProductRepository products) {
        this.customerPrices = customerPrices;
        this.promotions     = promotions;
        this.priceTiers     = priceTiers;
        this.productPrices  = productPrices;
        this.products       = products;
    }

    @Override
    public ResolvedPriceDto resolve(ResolvePriceRequest req) {
        // 1 — Customer-specific price (highest priority)
        if (req.customerId() != null) {
            var cp = customerPrices.findActiveForCustomerProduct(
                    req.customerId(), req.productId(), req.businessDate());
            if (cp.isPresent()) {
                return ResolvedPriceDto.customerPrice(cp.get().getUnitPriceAmount(),
                        CurrencyCode.value(cp.get().getCurrency()));
            }
        }

        // 2 — Promotion (only applied when a list/tier price exists as the base)
        var listPrice = resolveListOrTier(req);
        if (listPrice != null) {
            var promoResult = applyBestPromotion(req, listPrice.unitPriceAmount(),
                    listPrice.currency());
            if (promoResult != null) {
                return promoResult;
            }
        }

        // 3 — Quantity-break tier
        if (req.priceListId() != null) {
            var tier = priceTiers.findBestTier(req.productId(), req.priceListId(), req.quantity());
            if (tier.isPresent()) {
                return ResolvedPriceDto.tier(tier.get().getUnitPriceAmount(),
                        CurrencyCode.value(tier.get().getCurrency()));
            }
        }

        // 4 — Plain list price
        if (listPrice != null) {
            return listPrice;
        }

        // 5 — NONE
        return ResolvedPriceDto.none();
    }

    // ---- helpers ---------------------------------------------------------------

    private ResolvedPriceDto resolveListOrTier(ResolvePriceRequest req) {
        if (req.priceListId() == null) {
            return null;
        }
        // Check plain list price first
        var pp = productPrices.findByProductIdAndPriceListId(req.productId(), req.priceListId());
        if (pp.isPresent()) {
            var price = pp.get().getPrice();
            return ResolvedPriceDto.listPrice(price.getAmount(), price.getCurrency().value());
        }
        return null;
    }

    /**
     * Find the highest-priority active promotion that matches this product, returning
     * a ResolvedPriceDto with PROMOTION source, or null if none applies.
     */
    private ResolvedPriceDto applyBestPromotion(ResolvePriceRequest req,
                                                BigDecimal basePrice, String currency) {
        List<Promotion> candidates = promotions.findActiveForProduct(
                req.companyId(), req.productId(), req.businessDate());
        if (candidates.isEmpty()) {
            return null;
        }

        // Fetch the product's category for CATEGORY-target filtering
        String category = products.findById(req.productId())
                .map(p -> p.getCategory())
                .orElse(null);

        for (Promotion promo : candidates) {
            if (promo.getTarget() == PromotionTarget.CATEGORY) {
                // Only apply if product's category matches the promo's target category
                if (category == null || !category.equalsIgnoreCase(promo.getTargetCategory())) {
                    continue;
                }
            }
            // ALL and PRODUCT targets already filtered by the JPQL query
            return applyEffect(promo, basePrice, currency);
        }
        return null;
    }

    private ResolvedPriceDto applyEffect(Promotion promo, BigDecimal basePrice, String currency) {
        BigDecimal effectValue = promo.getEffectValue();
        BigDecimal finalPrice = switch (promo.getEffect()) {
            case PERCENT_DISCOUNT -> {
                BigDecimal discount = basePrice.multiply(effectValue)
                        .divide(BigDecimal.valueOf(100), 4, RoundingMode.HALF_UP);
                yield basePrice.subtract(discount);
            }
            case AMOUNT_DISCOUNT -> basePrice.subtract(effectValue).max(BigDecimal.ZERO);
            case OVERRIDE_PRICE  -> effectValue;
        };

        BigDecimal discountAmount = null;
        BigDecimal discountPercent = null;
        if (promo.getEffect() == PromotionEffect.PERCENT_DISCOUNT) {
            discountPercent = effectValue;
            discountAmount  = basePrice.subtract(finalPrice);
        } else if (promo.getEffect() == PromotionEffect.AMOUNT_DISCOUNT) {
            discountAmount  = effectValue.min(basePrice);
        }

        return new ResolvedPriceDto(finalPrice, discountAmount, discountPercent, currency,
                com.erp.modules.products.domain.enums.PriceSource.PROMOTION);
    }
}
