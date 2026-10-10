package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.ResolvePriceRequest;
import com.erp.modules.products.domain.dto.ResolvedPriceDto;
import com.erp.modules.products.domain.dto.SellingPriceQuery;
import com.erp.modules.products.domain.dto.UnitListPriceDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteDto;
import com.erp.modules.products.domain.dto.UnitPriceQuoteResult;
import com.erp.modules.products.domain.entity.CustomerPrice;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.entity.PriceTier;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.ProductBulkPack;
import com.erp.modules.products.domain.entity.ProductPrice;
import com.erp.modules.products.domain.entity.Promotion;
import com.erp.modules.products.domain.enums.PriceSource;
import com.erp.modules.products.domain.enums.PromotionEffect;
import com.erp.modules.products.domain.enums.PromotionTarget;
import com.erp.modules.products.domain.enums.UnitPriceStatus;
import com.erp.modules.products.repository.CustomerPriceRepository;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.ProductBulkPackRepository;
import com.erp.modules.products.repository.ProductPriceRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.PriceTierRepository;
import com.erp.modules.products.repository.PromotionRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Deterministic price resolver. The live selling path is {@link #findSellingPriceQuote}: customer
 * contract price &gt; list price (customer list &gt; company default list &gt; legacy lowest-id row),
 * PRD-01. {@link #resolve} (ADR-0029 D-6, adding promotions and tiers) stays unwired. Read-only
 * transaction.
 */
@Service
@Transactional(readOnly = true)
public class PriceResolutionServiceImpl implements PriceResolutionService {

    /**
     * Shown when nothing in the catalogue can price the line. The previous wording ("Product has no
     * price configured for this company.") stated the problem and stopped there — on a company with
     * no price list at all, which is every company on day one, it was the only thing a salesperson
     * ever saw and it named no way out. Both ways out are now in the message: configure the
     * catalogue, or state the price on the line (every sales document takes a unit price).
     */
    static final String NO_PRICE_MESSAGE =
            "This product has no price yet — no price list is configured for this company. "
                    + "Set up a price list, or enter a unit price on the line.";

    static final String UNIT_NOT_APPLICABLE_MESSAGE =
            "This unit is not valid for this product. Use the product's base unit or "
                    + "a configured pack unit.";

    private final CustomerPriceRepository customerPrices;
    private final PromotionRepository     promotions;
    private final PriceTierRepository     priceTiers;
    private final ProductPriceRepository  productPrices;
    private final ProductBulkPackRepository bulkPacks;
    private final ProductRepository       products;
    private final PriceListRepository     priceLists;

    public PriceResolutionServiceImpl(CustomerPriceRepository customerPrices,
                                      PromotionRepository promotions,
                                      PriceTierRepository priceTiers,
                                      ProductPriceRepository productPrices,
                                      ProductBulkPackRepository bulkPacks,
                                      ProductRepository products,
                                      PriceListRepository priceLists) {
        this.customerPrices = customerPrices;
        this.promotions     = promotions;
        this.priceTiers     = priceTiers;
        this.productPrices  = productPrices;
        this.bulkPacks      = bulkPacks;
        this.products       = products;
        this.priceLists     = priceLists;
    }

    @Override
    public ResolvedPriceDto resolve(ResolvePriceRequest req) {
        // 1 — Customer-specific price (highest priority)
        if (req.customerId() != null) {
            var cp = customerPrices.findActiveForCustomerProduct(
                    req.customerId(), req.productId(),
                    req.businessDate() != null ? req.businessDate() : LocalDate.now());
            if (cp.isPresent()) {
                CustomerPrice customerPrice = cp.get();
                return ResolvedPriceDto.customerPrice(customerPrice.getUnitPriceAmount(),
                        CurrencyCode.value(customerPrice.getCurrency()),
                        vatInclusiveOf(req.companyId(), customerPrice.getPriceListId()));
            }
        }

        // 2 — Promotion (only applied when a list/tier price exists as the base)
        var listPrice = resolveListOrTier(req);
        if (listPrice != null) {
            var promoResult = applyBestPromotion(req, listPrice.unitPriceAmount(), listPrice.currency());
            if (promoResult != null) {
                // ADR-0056: a promotion is a modifier on top of a list price, not a new stance —
                // inherit the underlying list price's VAT-inclusive flag. Applied here (not
                // threaded through applyBestPromotion/applyEffect) to avoid changing those methods'
                // signatures (ArchUnit TenantScopingRulesTest freezes violations by method
                // descriptor, incl. the pre-existing frozen findById call inside applyBestPromotion).
                return new ResolvedPriceDto(promoResult.unitPriceAmount(), promoResult.ruleDiscountAmount(),
                        promoResult.ruleDiscountPercent(), promoResult.currency(), promoResult.priceSource(),
                        listPrice.vatInclusive());
            }
        }

        // 3 — Quantity-break tier
        if (req.priceListId() != null) {
            var tier = priceTiers.findBestTier(req.productId(), req.priceListId(), req.quantity());
            if (tier.isPresent()) {
                PriceTier priceTier = tier.get();
                return ResolvedPriceDto.tier(priceTier.getUnitPriceAmount(),
                        CurrencyCode.value(priceTier.getCurrency()),
                        vatInclusiveOf(req.companyId(), priceTier.getPriceListId()));
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
    public UnitListPriceDto resolveUnitListPrice(Long companyId, Long productId, Long unitId) {
        // Single implementation of the unit-aware rules lives in resolveUnitListPriceQuote; this
        // narrower shape is what the three sales services consume (currency comes from the header).
        UnitPriceQuoteDto quote = resolveUnitListPriceQuote(companyId, productId, unitId);
        return new UnitListPriceDto(quote.amount(), quote.vatInclusive());
    }

    @Override
    public UnitPriceQuoteDto resolveUnitListPriceQuote(Long companyId, Long productId, Long unitId) {
        // Thin throwing façade over the single non-throwing implementation below — sales documents
        // price one line at a time and treat "unpriceable" as an error, so they keep this shape.
        UnitPriceQuoteResult result = findUnitListPriceQuote(companyId, productId, unitId);
        return switch (result.status()) {
            case RESOLVED -> result.price();
            case NO_PRICE -> throw new IllegalArgumentException(NO_PRICE_MESSAGE);
            case UNIT_NOT_APPLICABLE -> throw new IllegalStateException(UNIT_NOT_APPLICABLE_MESSAGE);
        };
    }

    @Override
    public UnitPriceQuoteResult findUnitListPriceQuote(Long companyId, Long productId, Long unitId) {
        // The walk-in question: no customer, any currency, today. One implementation of the rules —
        // this used to be its own "first price row ever created wins" scan (PRD-01).
        return findSellingPriceQuote(SellingPriceQuery.walkIn(companyId, productId, unitId));
    }

    @Override
    public UnitListPriceDto resolveSellingPrice(SellingPriceQuery query) {
        UnitPriceQuoteResult result = findSellingPriceQuote(query);
        return switch (result.status()) {
            case RESOLVED -> new UnitListPriceDto(result.price().amount(),
                    result.price().vatInclusive(), result.price().source());
            case NO_PRICE -> throw new IllegalArgumentException(NO_PRICE_MESSAGE);
            case UNIT_NOT_APPLICABLE -> throw new IllegalStateException(UNIT_NOT_APPLICABLE_MESSAGE);
        };
    }

    @Override
    public UnitPriceQuoteResult findSellingPriceQuote(SellingPriceQuery query) {
        // Company-scoped finder (not bare findById) — prevents a confused-deputy cross-tenant
        // read if a caller ever passes a productId that isn't actually in companyId.
        Product product = products.findByCompanyIdAndId(query.companyId(), query.productId())
                .orElseThrow(() -> new NotFoundException("Product not found."));
        LocalDate date = query.businessDate() != null ? query.businessDate() : LocalDate.now();

        // null = the unit is neither the base nor a configured pack (mirrors computeQtyInBase).
        BigDecimal factor = unitFactor(product, query.unitId());

        // Every row that may price a sale today: ACTIVE list, inside the list's and the row's own
        // validity windows, carrying an amount. Oldest first, so "first" keeps meaning lowest id.
        List<ProductPrice> usable = productPrices
                .findPricingRowsOfProduct(query.companyId(), query.productId()).stream()
                .filter(row -> SellingPriceRules.rowUsable(row, date))
                .toList();

        UnitPriceQuoteResult listPrice = resolveListPrice(product, query, factor, usable);

        // 1 — Customer contract price (PRD-02, customer prices only). Applies to the named customer
        // alone, so a walk-in sale — and the till's preview of it — is never affected.
        if (query.customerId() != null) {
            Optional<UnitPriceQuoteResult> contract =
                    customerContractPrice(product, query, factor, date, listPrice);
            if (contract.isPresent()) {
                return contract.get();
            }
        }
        // 2 — List price: customer list > company default list > legacy lowest-id row.
        return listPrice;
    }

    // ---- selling-price resolution (PRD-01) ------------------------------------------

    /**
     * The list price, choosing the list customer default &gt; company default &gt; legacy lowest-id
     * row, preferring rows in the document currency (see the interface for the full contract).
     */
    private UnitPriceQuoteResult resolveListPrice(Product product, SellingPriceQuery query,
                                                  BigDecimal factor, List<ProductPrice> usable) {
        List<List<ProductPrice>> passes = new ArrayList<>(2);
        if (query.currency() != null) {
            // Rows in the document's currency first. A TZS invoice must not be priced from a USD row
            // while a TZS one exists — that charges the USD number as shillings.
            passes.add(usable.stream()
                    .filter(row -> SellingPriceRules.currencyMatches(row, query.currency()))
                    .toList());
        }
        // Then every usable row: the long-standing tolerance for a single-currency shop whose rows
        // were saved under another code (the till does the same). Reached only when no row in the
        // document currency could price the line.
        passes.add(usable);

        boolean unitNotApplicable = false;
        for (List<ProductPrice> rows : passes) {
            if (rows.isEmpty()) {
                continue;
            }
            List<Long> listOrder = new ArrayList<>(3);
            if (query.customerPriceListId() != null) {
                listOrder.add(query.customerPriceListId());
            }
            companyDefaultListId(rows).ifPresent(listOrder::add);
            listOrder.add(null); // legacy: any list, lowest row id

            for (Long listId : listOrder) {
                UnitPriceQuoteResult result = priceOnRows(product, query.unitId(), factor, rows, listId);
                if (result == null) {
                    continue; // nothing on this list for the product — try the next tier
                }
                if (result.isResolved()) {
                    return result;
                }
                // A unit the product is not sold in is a product-level fact; another list can only
                // rescue it with an explicit row for that very unit. Keep looking, remember why.
                unitNotApplicable = true;
            }
        }
        return UnitPriceQuoteResult.unpriced(unitNotApplicable
                ? UnitPriceStatus.UNIT_NOT_APPLICABLE
                : UnitPriceStatus.NO_PRICE);
    }

    /**
     * The company default among the lists that can price this product today. Several lists can
     * carry the flag (there is no unique index behind it); the lowest id wins, deterministically.
     */
    private static Optional<Long> companyDefaultListId(List<ProductPrice> rows) {
        return rows.stream()
                .map(ProductPrice::getPriceList)
                .filter(PriceList::isDefault)
                .map(PriceList::getId)
                .filter(Objects::nonNull)
                .min(Long::compare);
    }

    /**
     * Prices one line from {@code rows}, restricted to price list {@code listId} (null = any list,
     * the legacy tier). ADR-0048: an explicit per-unit row for a pack wins (non-linear pack price,
     * inheriting ITS OWN list's VAT stance), else the base row × {@code factor_to_base}.
     *
     * @return the resolved quote; {@code UNIT_NOT_APPLICABLE} when a base row exists but the unit
     *         is not one the product is sold in; {@code null} when these rows hold no price at all
     */
    private static UnitPriceQuoteResult priceOnRows(Product product, Long unitId, BigDecimal factor,
                                                    List<ProductPrice> rows, Long listId) {
        Long baseUnitId = product.getBaseUnit().getId();
        List<ProductPrice> candidates = listId == null
                ? rows
                : rows.stream().filter(row -> listId.equals(row.getPriceList().getId())).toList();
        if (candidates.isEmpty()) {
            return null;
        }

        // 1 — explicit per-unit override (non-linear pack price), if configured for this unit.
        if (!baseUnitId.equals(unitId)) {
            Optional<ProductPrice> explicit = firstWithUnit(candidates, unitId);
            if (explicit.isPresent()) {
                return UnitPriceQuoteResult.resolved(quoteOf(explicit.get(), BigDecimal.ONE));
            }
        }

        // 2 — base row × factor_to_base(unit); factor is 1 for the base unit itself.
        //
        // A base price is SUPPOSED to live on the NULL row — resolvePriceUnit coerces a base-unit uid
        // to null on every write (ADR-0048 D-1). But a row can end up keyed on the base unit id
        // anyway: written against a PACK unit, correctly, before the product's base unit was changed
        // to that same unit. Step 1 skips such a row precisely because the caller asked for the base
        // unit, so without this tolerance the price falls between the two branches and the till
        // prices the line at nothing (a shop lost a morning to it on 2026-08-16). Read-side tolerance
        // rather than a data migration; the NULL row still wins when both exist, so correctly-keyed
        // data behaves identically.
        Optional<ProductPrice> base = candidates.stream()
                .filter(row -> row.getUnit() == null)
                .findFirst();
        if (base.isEmpty()) {
            base = firstWithUnit(candidates, baseUnitId);
        }
        if (base.isEmpty()) {
            return null;
        }
        // 3 — unit must be the base or a configured pack, else reject (mirrors computeQtyInBase).
        if (factor == null) {
            return UnitPriceQuoteResult.unpriced(UnitPriceStatus.UNIT_NOT_APPLICABLE);
        }
        return UnitPriceQuoteResult.resolved(quoteOf(base.get(), factor));
    }

    private static Optional<ProductPrice> firstWithUnit(List<ProductPrice> rows, Long unitId) {
        return rows.stream()
                .filter(row -> row.getUnit() != null && row.getUnit().getId().equals(unitId))
                .findFirst();
    }

    private static UnitPriceQuoteDto quoteOf(ProductPrice row, BigDecimal factor) {
        PriceList list = row.getPriceList();
        return new UnitPriceQuoteDto(row.getPrice().getAmount().multiply(factor), currencyOf(row),
                list.isPriceIncludesVat(), PriceSource.LIST_PRICE, list.getUid(), list.getName());
    }

    /**
     * The customer's contract price for this line, when one applies (see the interface). Empty when
     * none is configured, it belongs to another company, it is in another currency than the
     * document, or the line is below its minimum quantity — the list price then stands.
     */
    private Optional<UnitPriceQuoteResult> customerContractPrice(Product product,
                                                                 SellingPriceQuery query,
                                                                 BigDecimal factor, LocalDate date,
                                                                 UnitPriceQuoteResult listPrice) {
        Optional<CustomerPrice> found = customerPrices
                .findActiveForCustomerProduct(query.customerId(), product.getId(), date)
                // Defence in depth: the customer id came from a document; never trust it across
                // tenants.
                .filter(cp -> query.companyId().equals(cp.getCompanyId()))
                .filter(cp -> cp.getUnitPriceAmount() != null)
                .filter(cp -> query.currency() == null
                        || query.currency().equalsIgnoreCase(CurrencyCode.value(cp.getCurrency())));
        if (found.isEmpty()) {
            return Optional.empty();
        }
        if (factor == null) {
            // A contract price cannot make a unit sellable that the product is not sold in.
            return Optional.of(UnitPriceQuoteResult.unpriced(UnitPriceStatus.UNIT_NOT_APPLICABLE));
        }
        CustomerPrice contract = found.get();
        BigDecimal lineQty = query.quantity() != null ? query.quantity() : BigDecimal.ONE;
        if (contract.getMinQty() != null
                && lineQty.multiply(factor).compareTo(contract.getMinQty()) < 0) {
            return Optional.empty();
        }

        // VAT stance: the contract's own list when it names one; otherwise the stance of the list
        // price this customer would otherwise pay — the contract price is entered on the same basis
        // as the prices that customer sees, so a VAT-inclusive shop's "650" stays VAT-inclusive.
        boolean vatInclusive;
        String listUid = null;
        String listName = null;
        Optional<PriceList> ownList = contract.getPriceListId() == null
                ? Optional.empty()
                : priceLists.findByCompanyIdAndId(query.companyId(), contract.getPriceListId());
        if (ownList.isPresent()) {
            vatInclusive = ownList.get().isPriceIncludesVat();
            listUid = ownList.get().getUid();
            listName = ownList.get().getName();
        } else {
            vatInclusive = listPrice.isResolved() && listPrice.price().vatInclusive();
        }
        // Stored per base unit (customer_prices has no unit column), so a pack line pays
        // amount × factor_to_base.
        return Optional.of(UnitPriceQuoteResult.resolved(new UnitPriceQuoteDto(
                contract.getUnitPriceAmount().multiply(factor),
                CurrencyCode.value(contract.getCurrency()), vatInclusive,
                PriceSource.CUSTOMER_PRICE, listUid, listName)));
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
            ProductPrice productPrice = pp.get();
            var price = productPrice.getPrice();
            return ResolvedPriceDto.listPrice(price.getAmount(), price.getCurrency().value(),
                    productPrice.getPriceList().isPriceIncludesVat());
        }
        return null;
    }

    /**
     * ISO 4217 code of the price row. {@code SellingPriceRules.rowUsable} has already rejected a row
     * without a {@code Money}, so the price is present whenever this is reached.
     */
    private static String currencyOf(ProductPrice pp) {
        return CurrencyCode.value(pp.getPrice().getCurrency());
    }

    /**
     * VAT-inclusive stance of a soft-FK'd price list (ADR-0056) — used by the dormant
     * customer-price/tier branches of {@link #resolve} which store {@code priceListId} as a
     * scalar (no lazy JPA relation). {@code null} = not derived from a price list → exclusive
     * (the historical, only-ever-supported reading). Company-scoped (not bare {@code findById})
     * so a soft-FK'd id can never resolve a foreign company's price list (TenantScopingRulesTest).
     */
    private boolean vatInclusiveOf(Long companyId, Long priceListId) {
        if (priceListId == null) {
            return false;
        }
        return priceLists.findByCompanyIdAndId(companyId, priceListId)
                .map(PriceList::isPriceIncludesVat)
                .orElse(false);
    }

    /**
     * Factor-to-base multiplier for {@code unitId} against {@code product}'s base unit — mirrors
     * the sales modules' {@code computeQtyInBase} guard: base unit → 1, configured pack → its
     * factor, any other unit → {@code null} (not applicable). Returns {@code null} rather than
     * throwing so batch callers never trip the transaction (see {@code UnitPriceQuoteResult}); the
     * throwing façade turns it back into an {@code IllegalStateException}.
     */
    private BigDecimal unitFactor(Product product, Long unitId) {
        if (product.getBaseUnit().getId().equals(unitId)) {
            return BigDecimal.ONE;
        }
        return bulkPacks.findByProductId(product.getId()).stream()
                .filter(bp -> bp.getUnit().getId().equals(unitId))
                .map(ProductBulkPack::getFactorToBase)
                .findFirst()
                .orElse(null);
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

        // vatInclusive placeholder here — resolve() overwrites it with the underlying list price's
        // flag (ADR-0056; kept out of this method's signature deliberately, see the call site).
        return new ResolvedPriceDto(finalPrice, discountAmount, discountPercent, currency,
                com.erp.modules.products.domain.enums.PriceSource.PROMOTION, false);
    }
}
