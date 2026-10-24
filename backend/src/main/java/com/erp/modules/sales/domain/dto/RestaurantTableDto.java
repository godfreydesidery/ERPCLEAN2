package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.RestaurantTableState;
import com.erp.platform.common.domain.MasterStatus;

/**
 * A floor table with its live state (ADR-0065 D-3). {@code state} and {@code liveOrderCount} are
 * derived from the branch's OPEN/BILLED orders at read time — one table may hold several parties.
 * {@code readyItemCount} (ADR-0066 D-6) is how many of its items the kitchen has marked ready —
 * the floor shows "Food ready".
 */
public record RestaurantTableDto(
        Long id,
        String uid,
        Long branchId,
        String name,
        Short seats,
        String area,
        Integer sortOrder,
        MasterStatus status,
        RestaurantTableState state,
        int liveOrderCount,
        int readyItemCount
) {}
