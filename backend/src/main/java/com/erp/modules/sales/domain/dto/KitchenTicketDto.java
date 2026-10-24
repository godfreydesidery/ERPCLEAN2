package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.RestaurantOrderType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

/**
 * One ticket on the kitchen display (ADR-0066 D-6): one order's items for one station from one
 * send round, not yet bumped. Items voided after sending stay on it, struck through, until bumped,
 * so the cook sees the cancellation.
 */
public record KitchenTicketDto(
        String orderUid,
        String orderNumber,
        RestaurantOrderType orderType,
        String tableName,
        Short guestCount,
        int round,
        Instant sentAt,
        String sentByName,
        List<Item> items
) {
    /** An item with its priced add-ons listed under it. */
    public record Item(
            String lineUid,
            String productName,
            BigDecimal quantity,
            String note,
            int course,
            boolean voided,
            String voidReason,
            List<AddOn> addOns
    ) {}

    public record AddOn(String lineUid, String productName, BigDecimal quantity, boolean voided) {}
}
