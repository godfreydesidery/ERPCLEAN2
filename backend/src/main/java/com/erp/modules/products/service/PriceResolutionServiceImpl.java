package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ResolvePriceRequest;
import com.erp.modules.products.domain.dto.ResolvedPriceDto;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.Promotion;
import com.erp.modules.products.domain.enums.PromotionEffect;
import com.erp.modules.products.domain.enums.PromotionTarget;
import com.erp.modules.products.repository.CustomerPriceRepository;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.PriceTierRepository;
import com.erp.modules.products.repository.PromotionRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;
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
    private final ProductBulkPackRepository bulkPacks;
    private final ProductRepository       products;

    public PriceResolutionServiceImpl(CustomerPriceRepository customerPrices,
                                      PromotionRepository promotions,
                                      PriceTierRepository priceTiers,
                                      ProductPriceRepository productPrices,
                                      ProductBulkPackRepository bulkPacks,
                                      ProductRepository products) {
        this.customerPrices = customerPrices;
        this.promotions     = promotions;
        this.priceTiers     = priceTiers;
        this.productPrices  = productPrices;
        this.bulkPacks      = bulkPacks;
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

    @Override
    public BigDecimal resolveUnitListPrice(Long companyId, Long productId, Long unitId) {
        // Company-scoped finder (not bare findById) — prevents a confused-deputy cross-tenant
        // read if a caller ever passes a productId that isn't actually in companyId.
        Product product = products.findByCompanyIdAndId(companyId, productId)
                .orElseThrow(() -> new NotFoundException("Product not found."));

        // 1 — explicit per-unit override (non-linear pack price), if configured for this unit.
        boolean isBaseUnit = product.getBaseUnit().getId().equals(unitId);
        if (!isBaseUnit) {
            Optional<ProductPrice> explicit =
                    productPrices.findFirstByProductIdAndUnitIdOrderByIdAsc(productId, unitId);
            if (explicit.isPresent()) {
                return requireAmount(explicit.get());
            }
        }

        // 2 — base row × factor_to_base(unit); factor is 1 for the base unit itself.
        // First-wins across price lists: the live path is deliberately price-list-blind (ADR-0048),
        // and a product priced on several lists has several base rows — take the lowest-id one rather
        // than throwing on a multi-price-list product (restores the pre-D-1 findFirst tolerance).
        ProductPrice base = productPrices.findFirstByProductIdAndUnitIdIsNullOrderByIdAsc(productId)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Product has no price configured for this company."));
        BigDecimal baseAmount = requireAmount(base);

        // 3 — unit must be the base or a configured pack, else reject (mirrors computeQtyInBase).
        return baseAmount.multiply(unitFactor(product, unitId));
    }

    // ---- helpers ---------------------------------------------------------------

    /**
     * Base-unit list price, price-list scoped (ADR-0048 D-1: the ambiguous
     * {@code findByProductIdAndPriceListId} is replaced with the disambiguated base variant so this
     * dead path compiles and behaves correctly now that a product can carry more than one
     * {@code product_prices} row per list). Per-unit/non-linear resolution for this price-list-aware
     * path is left to the follow-up that activates this resolver — D-1 only fixes sales' unit-blind
     * live path via {@link #resolveUnitListPrice}.
     */
    private ResolvedPriceDto resolveListOrTier(ResolvePriceRequest req) {
        if (req.priceListId() == null) {
            return null;
        }
        var pp = productPrices.findByProductIdAndPriceListIdAndUnitIdIsNull(
                req.productId(), req.priceListId());
        if (pp.isPresent()) {
            var price = pp.get().getPrice();
            return ResolvedPriceDto.listPrice(price.getAmount(), price.getCurrency().value());
        }
        return null;
    }

    /** Extracts the price amount, rejecting a row whose Money is incomplete. */
    private static BigDecimal requireAmount(ProductPrice pp) {
        var money = pp.getPrice();
        if (money == null || money.getAmount() == null) {
            throw new IllegalArgumentException("Product has no price configured for this company.");
        }
        return money.getAmount();
    }

    /**
     * Factor-to-base multiplier for {@code unitId} against {@code product}'s base unit — mirrors
     * the sales modules' {@code computeQtyInBase} guard: base unit → 1, configured pack → its
     * factor, any other unit → rejected.
     */
    private BigDecimal unitFactor(Product product, Long unitId) {
        if (product.getBaseUnit().getId().equals(unitId)) {
            return BigDecimal.ONE;
        }
        return bulkPacks.findByProductId(product.getId()).stream()
                .filter(bp -> bp.getUnit().getId().equals(unitId))
                .map(ProductBulkPack::getFactorToBase)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "This unit is not valid for this product. Use the product's base unit or "
                                + "a configured pack unit."));
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
