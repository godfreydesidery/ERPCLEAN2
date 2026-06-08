package com.erp.modules.routes.domain.dto;

import com.erp.modules.routes.domain.entity.Route;
import com.erp.platform.common.domain.MasterStatus;

/**
 * Lightweight route summary for list/search results and invoice pick-lists (ADR-0012 D-11).
 */
public record RouteSummaryDto(
        Long id,
        String uid,
        String code,
        String name,
        String locationIdentifier,
        MasterStatus status
) {

    public static RouteSummaryDto from(Route r) {
        return new RouteSummaryDto(
                r.getId(),
                r.getUid(),
                r.getCode(),
                r.getName(),
                r.getLocationIdentifier(),
                r.getStatus()
        );
    }
}
