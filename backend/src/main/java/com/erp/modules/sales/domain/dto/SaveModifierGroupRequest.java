package com.erp.modules.sales.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Creates or replaces a modifier group in one save (ADR-0066 D-4): its rule, its options and the
 * menu items it is offered on.
 *
 * <p>Options are matched by {@code uid}: a listed uid is updated, a new row (no uid) is added, and an
 * existing option missing from the list is deactivated — never deleted, so past orders keep their
 * meaning. {@code productUids} replaces the group's item list. {@code maxSelect} null = no limit.
 */
public record SaveModifierGroupRequest(
        @NotBlank @Size(max = 60) String name,
        @Min(0) @Max(20) Short minSelect,
        @Min(1) @Max(20) Short maxSelect,
        Integer sortOrder,
        MasterStatus status,
        @NotNull @Valid List<Option> options,
        @NotNull List<String> productUids
) {
    public record Option(
            String uid,
            @NotBlank @Size(max = 60) String label,
            String productUid,
            Integer sortOrder
    ) {}
}
