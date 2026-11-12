package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Sets where ONE product category (department) prints, without touching the rest of the branch's
 * routing: the till's add-product dialog and the web product form. {@code branchUid} null means the
 * caller's active branch.
 */
public record SetCategoryRouteRequest(
        String branchUid,
        @NotBlank @Size(max = 60) String category,
        @NotBlank String prepStationUid
) {}
