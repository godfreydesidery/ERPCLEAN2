package com.erp.modules.sales.domain.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Replaces a branch's whole category → station routing in one save (ADR-0065 D-4). An empty list
 * clears it: every item then goes to the default station. {@code branchUid} null means the
 * caller's active branch.
 */
public record ReplaceCategoryRoutesRequest(
        String branchUid,
        @NotNull @Valid List<Route> routes
) {
    public record Route(
            @NotBlank @Size(max = 60) String category,
            @NotBlank String prepStationUid
    ) {}
}
