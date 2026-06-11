package com.erp.modules.approvals.domain.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Input for one step in a policy create/update request (ADR-0022 D-3).
 * Sequences must be dense from 1, unique per policy — validated in the service.
 */
public record PolicyStepInputDto(
        @NotNull @Min(1) Integer sequence,
        @NotBlank String approverRoleCode
) {}
