package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.RestaurantTableState;
import com.erp.platform.common.domain.MasterStatus;

/**
 * A floor table with its live state (ADR-0065 D-3). {@code state} and {@code liveOrderCount} are
 * derived from the branch's OPEN/BILLED orders at read time — one table may hold several parties.
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
        int liveOrderCount
) {}
