package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.RestaurantOrderLineStatus;
import java.math.BigDecimal;
import java.time.Instant;

/**
 * One item on a restaurant order (ADR-0065 D-5). The station is set once the item is sent.
 *
 * <p>Phase 3 (ADR-0066): {@code parentLineUid} marks a priced add-on and names the item it belongs
 * to; {@code course}, {@code held}, and {@code readyAt} (the kitchen display bumped it).
 */
public record RestaurantOrderLineDto(
        Long id,
        String uid,
        Integer lineNo,
        Long productId,
        String productUid,
        String productCode,
        String productName,
        Long unitId,
        String unitName,
        BigDecimal quantity,
        String note,
        RestaurantOrderLineStatus status,
        Short fireRound,
        String prepStationUid,
        String prepStationName,
        Instant sentAt,
        Instant voidedAt,
        String voidReason,
        String parentLineUid,
        Short course,
        boolean held,
        Instant readyAt
) {}
