package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * An order not yet paid: still OPEN, or BILLED (bill printed) and waiting for payment.
 * {@code billedAmount} is the printed bill's total, null until a bill is printed.
 */
public record RestaurantPendingBillRowDto(
        String     orderUid,
        String     orderNumber,
        String     servedAt,
        String     waiterName,
        String     status,
        String     openedAt,
        String     billedAt,
        BigDecimal items,
        BigDecimal billedAmount) {}
