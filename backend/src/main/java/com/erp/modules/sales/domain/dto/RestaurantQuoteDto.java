package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/** An order priced as it would be paid; send {@code grossTotal} back as {@code expectedTotal}. */
public record RestaurantQuoteDto(
        String orderUid,
        String currency,
        BigDecimal undiscountedTotal,
        BigDecimal discountTotal,
        BigDecimal netTotal,
        BigDecimal vatTotal,
        BigDecimal grossTotal
) {}
