package com.erp.modules.routes.domain.dto;

import com.erp.modules.routes.domain.entity.Route;
import com.erp.platform.common.domain.MasterStatus;

/**
 * Full response DTO for a Route master (ADR-0012 D-11).
 * Carries both {@code id} (serialised as JSON string via global config) and {@code uid}.
 */
public record RouteDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        String name,
        String locationIdentifier,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static RouteDto from(Route r) {
        return new RouteDto(
                r.getId(),
                r.getUid(),
                r.getCompanyId(),
                r.getCode(),
                r.getName(),
                r.getLocationIdentifier(),
                r.getStatus(),
                r.getVersion(),
                r.getCreatedAt() != null ? r.getCreatedAt().toString() : null,
                r.getCreatedBy(),
                r.getUpdatedAt() != null ? r.getUpdatedAt().toString() : null,
                r.getUpdatedBy()
        );
    }
}
