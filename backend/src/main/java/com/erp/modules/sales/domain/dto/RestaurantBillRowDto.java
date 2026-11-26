package com.erp.modules.sales.domain.dto;

import java.math.BigDecimal;

/**
 * One paid restaurant bill: the order, where it was served, the waiter who opened it, the cashier
 * who took the money, the receipt it became and how it was paid. {@code amount} is base currency and
 * <b>net of POS item returns</b> (ADR-0072); {@code returned} is the part given back (0 when none).
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
        String     tenders,
        BigDecimal returned,
        /**
         * What was bought, as sold: "2× Burger, Coffee, Cake" in line order (a quantity of 1 is left
         * unwritten). Additive — the daily sales log reads each bill at a glance.
         */
        String     items,
        /** The customer name typed on the bill at the till, or null when none was entered. Additive. */
        String     customerName) {

    /** The shape before {@code items} and {@code customerName}. */
    public RestaurantBillRowDto(String orderUid, String orderNumber, String servedAt,
                                String waiterName, String cashierName, String invoiceNumber,
                                String invoiceStatus, Integer guests, String openedAt, String paidAt,
                                BigDecimal amount, String tenders, BigDecimal returned) {
        this(orderUid, orderNumber, servedAt, waiterName, cashierName, invoiceNumber, invoiceStatus,
                guests, openedAt, paidAt, amount, tenders, returned, null, null);
    }
}
