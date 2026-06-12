package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.CreateCustomerPriceRequest;
import com.erp.modules.products.domain.dto.CreatePriceTierRequest;
import com.erp.modules.products.domain.dto.CreatePromotionRequest;
import com.erp.modules.products.domain.dto.CustomerPriceDto;
import com.erp.modules.products.domain.dto.PriceTierDto;
import com.erp.modules.products.domain.dto.PromotionDto;
import com.erp.modules.products.domain.entity.CustomerPrice;
import com.erp.modules.products.domain.entity.PriceTier;
import com.erp.modules.products.domain.entity.PriceList;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.entity.Promotion;
import com.erp.modules.products.domain.enums.PromotionEffect;
import com.erp.modules.products.domain.enums.PromotionTarget;
import com.erp.modules.products.repository.CustomerPriceRepository;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.repository.PriceTierRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.repository.PromotionRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * CRUD for pricing rules: price tiers, customer prices, promotions (ADR-0029 D-6, FR-SD-09/10/11).
 */
@Service
@Transactional
public class PricingRuleServiceImpl implements PricingRuleService {

    private final PriceTierRepository     tiers;
    private final CustomerPriceRepository customerPrices;
    private final PromotionRepository     promotions;
    private final ProductRepository       products;
    private final PriceListRepository     priceLists;
    private final CustomerRepository      customers;
    private final CompanyRepository       companies;
    private final ScopeGuard              scopeGuard;
    private final AuditService            audit;

    public PricingRuleServiceImpl(PriceTierRepository tiers,
                                   CustomerPriceRepository customerPrices,
                                   PromotionRepository promotions,
                                   ProductRepository products,
                                   PriceListRepository priceLists,
                                   CustomerRepository customers,
                                   CompanyRepository companies,
                                   ScopeGuard scopeGuard,
                                   AuditService audit) {
        this.tiers          = tiers;
        this.customerPrices = customerPrices;
        this.promotions     = promotions;
        this.products       = products;
        this.priceLists     = priceLists;
        this.customers      = customers;
        this.companies      = companies;
        this.scopeGuard     = scopeGuard;
        this.audit          = audit;
    }

    // ---- Price Tiers -----------------------------------------------------------

    @Override
    public PriceTierDto createTier(CreatePriceTierRequest req) {
        Product product = requireProductByUid(req.productUid());
        scopeGuard.assertCanActIn(RequestContext.get(), product.getCompanyId());
        PriceList pl = requirePriceListByUid(req.priceListUid(), product.getCompanyId());

        // Validate no duplicate break floor
        if (tiers.existsDuplicateBreak(product.getId(), pl.getId(), req.minQty(), -1L)) {
            throw new ConflictException("A tier with min_qty " + req.minQty() + " already exists for this product+price_list.");
        }

        PriceTier tier = new PriceTier(
                product.getCompanyId(), product.getId(), pl.getId(),
                req.minQty(), req.unitPriceAmount(), req.currency(),
                actorId());
        tier = tiers.save(tier);

        audit.record(AuditEvent.of(AuditActions.PRICING_TIER_CREATE, "price_tiers", tier.getId(), tier.getUid())
                .detail(Map.of("productId", String.valueOf(product.getId()),
                               "priceListId", String.valueOf(pl.getId()),
                               "minQty", req.minQty().toPlainString())));
        return toDto(tier);
    }

    @Override
    @Transactional(readOnly = true)
    public PriceTierDto getTierByUid(String uid) {
        PriceTier tier = requireTierByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), tier.getCompanyId());
        return toDto(tier);
    }

    @Override
    @Transactional(readOnly = true)
    public List<PriceTierDto> listTiers(Long companyId, Long productId, Long priceListId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return tiers.findByCompanyIdAndProductIdAndPriceListId(companyId, productId, priceListId)
                .stream().map(this::toDto).toList();
    }

    @Override
    public void deactivateTier(String uid) {
        PriceTier tier = requireTierByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), tier.getCompanyId());
        tier.setStatus(MasterStatus.INACTIVE);
        tier.setUpdatedAt(Instant.now());
        tier.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.PRICING_TIER_DEACTIVATE, "price_tiers", tier.getId(), tier.getUid()));
    }

    // ---- Customer Prices -------------------------------------------------------

    @Override
    public CustomerPriceDto createCustomerPrice(CreateCustomerPriceRequest req) {
        Product product = requireProductByUid(req.productUid());
        scopeGuard.assertCanActIn(RequestContext.get(), product.getCompanyId());
        var customer = customers.findByUid(req.customerUid())
                .orElseThrow(() -> NotFoundException.of("Customer", req.customerUid()));

        CustomerPrice cp = new CustomerPrice(
                product.getCompanyId(), customer.getId(), product.getId(),
                req.unitPriceAmount(), req.currency(),
                req.effectiveFrom(), req.effectiveTo(),
                actorId());
        cp = customerPrices.save(cp);

        audit.record(AuditEvent.of(AuditActions.CUSTOMER_PRICE_CREATE, "customer_prices", cp.getId(), cp.getUid())
                .detail(Map.of("customerId", String.valueOf(customer.getId()),
                               "productId", String.valueOf(product.getId()))));
        return toDto(cp);
    }

    @Override
    @Transactional(readOnly = true)
    public CustomerPriceDto getCustomerPriceByUid(String uid) {
        CustomerPrice cp = requireCustomerPriceByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), cp.getCompanyId());
        return toDto(cp);
    }

    @Override
    @Transactional(readOnly = true)
    public List<CustomerPriceDto> listCustomerPrices(Long companyId, Long customerId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return customerPrices.findByCompanyIdAndCustomerId(companyId, customerId)
                .stream().map(this::toDto).toList();
    }

    @Override
    public void deactivateCustomerPrice(String uid) {
        CustomerPrice cp = requireCustomerPriceByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), cp.getCompanyId());
        cp.setStatus(MasterStatus.INACTIVE);
        cp.setUpdatedAt(Instant.now());
        cp.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.CUSTOMER_PRICE_DEACTIVATE, "customer_prices", cp.getId(), cp.getUid()));
    }

    // ---- Promotions ------------------------------------------------------------

    @Override
    public PromotionDto createPromotion(CreatePromotionRequest req) {
        var company = requireCompanyId(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), company);

        Long targetProductId = null;
        if (req.target() == PromotionTarget.PRODUCT) {
            if (req.targetProductUid() == null || req.targetProductUid().isBlank()) {
                throw new IllegalArgumentException("targetProductUid required when target=PRODUCT");
            }
            targetProductId = requireProductByUid(req.targetProductUid()).getId();
        }

        validatePromoEffect(req.effect(), req.effectValue());

        Promotion promo = new Promotion(
                company, req.code(), req.name(),
                req.target(), targetProductId, req.targetCategory(),
                req.effect(), req.effectValue(),
                req.effectiveFrom(), req.effectiveTo(),
                req.priority(), actorId());
        promo = promotions.save(promo);

        audit.record(AuditEvent.of(AuditActions.PROMOTION_CREATE, "promotions", promo.getId(), promo.getUid())
                .detail(Map.of("code", req.code(), "effect", req.effect().name())));
        return toDto(promo);
    }

    @Override
    @Transactional(readOnly = true)
    public PromotionDto getPromotionByUid(String uid) {
        Promotion promo = requirePromotionByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), promo.getCompanyId());
        return toDto(promo);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<PromotionDto> listPromotions(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return promotions.findByCompanyId(companyId, pageable).map(this::toDto);
    }

    @Override
    public void deactivatePromotion(String uid) {
        Promotion promo = requirePromotionByUid(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), promo.getCompanyId());
        promo.setStatus(MasterStatus.INACTIVE);
        promo.setUpdatedAt(Instant.now());
        promo.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.PROMOTION_DEACTIVATE, "promotions", promo.getId(), promo.getUid()));
    }

    // ---- helpers ---------------------------------------------------------------

    private void validatePromoEffect(PromotionEffect effect, java.math.BigDecimal value) {
        if (effect == PromotionEffect.PERCENT_DISCOUNT) {
            if (value.compareTo(java.math.BigDecimal.ZERO) < 0 ||
                    value.compareTo(java.math.BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("effect_value must be 0–100 for PERCENT_DISCOUNT");
            }
        }
    }

    private PriceTier requireTierByUid(String uid) {
        return tiers.findByUid(uid).orElseThrow(() -> NotFoundException.of("PriceTier", uid));
    }

    private CustomerPrice requireCustomerPriceByUid(String uid) {
        return customerPrices.findByUid(uid).orElseThrow(() -> NotFoundException.of("CustomerPrice", uid));
    }

    private Promotion requirePromotionByUid(String uid) {
        return promotions.findByUid(uid).orElseThrow(() -> NotFoundException.of("Promotion", uid));
    }

    private Product requireProductByUid(String uid) {
        return products.findByUid(uid).orElseThrow(() -> NotFoundException.of("Product", uid));
    }

    private PriceList requirePriceListByUid(String uid, Long companyId) {
        return priceLists.findByUid(uid).orElseThrow(() -> NotFoundException.of("PriceList", uid));
    }

    private Long requireCompanyId(String companyUid) {
        return companies.findByUid(companyUid)
                .orElseThrow(() -> NotFoundException.of("Company", companyUid))
                .getId();
    }

    private Long actorId() {
        var principal = RequestContext.get();
        return (principal != null) ? principal.userId() : null;
    }

    private PriceTierDto toDto(PriceTier t) {
        return new PriceTierDto(t.getId(), t.getUid(), t.getCompanyId(),
                t.getProductId(), t.getPriceListId(),
                t.getMinQty(), t.getUnitPriceAmount(), t.getCurrency(), t.getStatus());
    }

    private CustomerPriceDto toDto(CustomerPrice cp) {
        return new CustomerPriceDto(cp.getId(), cp.getUid(), cp.getCompanyId(),
                cp.getCustomerId(), cp.getProductId(),
                cp.getUnitPriceAmount(), cp.getCurrency(),
                cp.getEffectiveFrom(), cp.getEffectiveTo(), cp.getStatus());
    }

    private PromotionDto toDto(Promotion p) {
        return new PromotionDto(p.getId(), p.getUid(), p.getCompanyId(),
                p.getCode(), p.getName(), p.getTarget(),
                p.getTargetProductId(), p.getTargetCategory(),
                p.getEffect(), p.getEffectValue(),
                p.getEffectiveFrom(), p.getEffectiveTo(),
                p.getPriority(), p.getStatus());
    }
}
