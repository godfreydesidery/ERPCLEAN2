package com.erp.api;

import com.erp.modules.products.domain.dto.CreateCustomerPriceRequest;
import com.erp.modules.products.domain.dto.CreatePriceTierRequest;
import com.erp.modules.products.domain.dto.CreatePromotionRequest;
import com.erp.modules.products.domain.dto.CustomerPriceDto;
import com.erp.modules.products.domain.dto.PriceTierDto;
import com.erp.modules.products.domain.dto.PromotionDto;
import com.erp.modules.products.service.PricingRuleService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRUD for advanced pricing rules: tiers, customer prices, promotions (ADR-0029 D-6).
 * Permissions seeded in V42__sales_pricing_rules.sql.
 */
@RestController
@RequestMapping("/api/v1/pricing-rules")
public class PricingRuleController {

    private final PricingRuleService pricingRules;

    public PricingRuleController(PricingRuleService pricingRules) {
        this.pricingRules = pricingRules;
    }

    // ---- Price Tiers -----------------------------------------------------------

    @PostMapping("/tiers")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('SALES.PRICING.RULE.MANAGE')")
    public PriceTierDto createTier(@Valid @RequestBody CreatePriceTierRequest request) {
        return pricingRules.createTier(request);
    }

    @GetMapping("/tiers/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'pricetier','SALES.PRICING.RULE.VIEW')")
    public PriceTierDto getTier(@PathVariable String uid) {
        return pricingRules.getTierByUid(uid);
    }

    @GetMapping("/tiers")
    @PreAuthorize("@perm.has('SALES.PRICING.RULE.VIEW')")
    public List<PriceTierDto> listTiers(@RequestParam Long companyId,
                                         @RequestParam Long productId,
                                         @RequestParam Long priceListId) {
        return pricingRules.listTiers(companyId, productId, priceListId);
    }

    @DeleteMapping("/tiers/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'pricetier','SALES.PRICING.RULE.MANAGE')")
    public void deactivateTier(@PathVariable String uid) {
        pricingRules.deactivateTier(uid);
    }

    // ---- Customer Prices -------------------------------------------------------

    @PostMapping("/customer-prices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('SALES.PRICING.RULE.MANAGE')")
    public CustomerPriceDto createCustomerPrice(@Valid @RequestBody CreateCustomerPriceRequest request) {
        return pricingRules.createCustomerPrice(request);
    }

    @GetMapping("/customer-prices/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'customerprice','SALES.PRICING.RULE.VIEW')")
    public CustomerPriceDto getCustomerPrice(@PathVariable String uid) {
        return pricingRules.getCustomerPriceByUid(uid);
    }

    @GetMapping("/customer-prices")
    @PreAuthorize("@perm.has('SALES.PRICING.RULE.VIEW')")
    public List<CustomerPriceDto> listCustomerPrices(@RequestParam Long companyId,
                                                      @RequestParam Long customerId) {
        return pricingRules.listCustomerPrices(companyId, customerId);
    }

    @DeleteMapping("/customer-prices/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'customerprice','SALES.PRICING.RULE.MANAGE')")
    public void deactivateCustomerPrice(@PathVariable String uid) {
        pricingRules.deactivateCustomerPrice(uid);
    }

    // ---- Promotions ------------------------------------------------------------

    @PostMapping("/promotions")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('SALES.PRICING.RULE.MANAGE')")
    public PromotionDto createPromotion(@Valid @RequestBody CreatePromotionRequest request) {
        return pricingRules.createPromotion(request);
    }

    @GetMapping("/promotions/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'promotion','SALES.PRICING.RULE.VIEW')")
    public PromotionDto getPromotion(@PathVariable String uid) {
        return pricingRules.getPromotionByUid(uid);
    }

    @GetMapping("/promotions")
    @PreAuthorize("@perm.has('SALES.PRICING.RULE.VIEW')")
    public ApiResponse<List<PromotionDto>> listPromotions(@RequestParam Long companyId,
                                                           Pageable pageable) {
        Page<PromotionDto> page = pricingRules.listPromotions(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @DeleteMapping("/promotions/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'promotion','SALES.PRICING.RULE.MANAGE')")
    public void deactivatePromotion(@PathVariable String uid) {
        pricingRules.deactivatePromotion(uid);
    }
}
