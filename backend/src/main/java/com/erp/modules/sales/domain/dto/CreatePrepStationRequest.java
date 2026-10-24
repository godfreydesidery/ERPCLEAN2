package com.erp.modules.sales.domain.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Adds a prep station to a branch (ADR-0065 D-4). {@code branchUid} null means the caller's active
 * branch. {@code printerPort} null means 9100, the ESC/POS raw-print default.
 */
public record CreatePrepStationRequest(
        String branchUid,
        @NotBlank @Size(max = 60) String name,
        @Size(max = 255) String printerHost,
        @Min(1) @Max(65535) Integer printerPort,
        boolean defaultStation
) {}
