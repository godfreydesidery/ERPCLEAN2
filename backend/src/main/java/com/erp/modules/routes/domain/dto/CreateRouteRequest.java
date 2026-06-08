package com.erp.modules.routes.domain.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * Request DTO to create a new route master (ADR-0012 D-11).
 * {@code companyUid} (String) in the body — consistent with Products/Sales convention (ADR-0007 D-12).
 */
public record CreateRouteRequest(
        @NotBlank String companyUid,
        @NotBlank String name,
        String locationIdentifier
) {
}
