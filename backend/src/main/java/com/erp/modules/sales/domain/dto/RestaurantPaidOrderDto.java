package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/** A completed (paid) order with what was actually paid and its invoice number. */
public record RestaurantPaidOrderDto(
        RestaurantOrderDto order,
        String invoiceNumber,
        BigDecimal paidTotal
) {}
