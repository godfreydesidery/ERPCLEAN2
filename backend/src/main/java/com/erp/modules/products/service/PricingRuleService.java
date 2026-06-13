package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.CreateCustomerPriceRequest;
import com.erp.modules.products.domain.dto.CreatePriceTierRequest;
import com.erp.modules.products.domain.dto.CreatePromotionRequest;
import com.erp.modules.products.domain.dto.CustomerPriceDto;
import com.erp.modules.products.domain.dto.PriceTierDto;
import com.erp.modules.products.domain.dto.PromotionDto;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * CRUD for pricing rules: price tiers, customer-specific prices, promotions (ADR-0029 D-6).
 */
public interface PricingRuleService {

    // ---- Price Tiers -----------------------------------------------------------
    PriceTierDto createTier(CreatePriceTierRequest request);

    PriceTierDto getTierByUid(String uid);

    List<PriceTierDto> listTiers(Long companyId, Long productId, Long priceListId);

    void deactivateTier(String uid);

    // ---- Customer Prices -------------------------------------------------------
    CustomerPriceDto createCustomerPrice(CreateCustomerPriceRequest request);

    CustomerPriceDto getCustomerPriceByUid(String uid);

    List<CustomerPriceDto> listCustomerPrices(Long companyId, Long customerId);

    void deactivateCustomerPrice(String uid);

    // ---- Promotions ------------------------------------------------------------
    PromotionDto createPromotion(CreatePromotionRequest request);

    PromotionDto getPromotionByUid(String uid);

    Page<PromotionDto> listPromotions(Long companyId, Pageable pageable);

    void deactivatePromotion(String uid);
}
