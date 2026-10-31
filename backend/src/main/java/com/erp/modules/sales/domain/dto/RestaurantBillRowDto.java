package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * One paid restaurant bill: the order, where it was served, the waiter who opened it, the cashier
 * who took the money, the receipt it became and how it was paid. {@code amount} is base currency.
 */
public record RestaurantBillRowDto(
        String     orderUid,
        String     orderNumber,
        String     servedAt,
        String     waiterName,
        String     cashierName,
        String     invoiceNumber,
        String     invoiceStatus,
        Integer    guests,
        String     openedAt,
        String     paidAt,
        BigDecimal amount,
        String     tenders) {}
