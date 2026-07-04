package com.erp.modules.stock.domain.dto;

import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.platform.common.domain.MasterStatus;

/**
 * Response DTO for a stock location (ADR-0028 D-4).
 * id + uid duality per PROJECT-CONVENTIONS; long ids serialised as strings globally.
 */
public record StockLocationDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String code,
        String name,
        LocationType locationType,
        boolean isDefault,
        // P2 D7 — location attributes
        Long parentLocationId,
        boolean allowNegative,
        boolean pickable,
        boolean sellable,
        Long glAccountId,
        MasterStatus status,
        // ADR-0051 D-8.4 — van/agent link
        String agentUid,
        String agentName
) {}
