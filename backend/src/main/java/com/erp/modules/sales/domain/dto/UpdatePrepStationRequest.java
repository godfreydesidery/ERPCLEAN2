package com.erp.modules.sales.domain.dto;

import com.erp.platform.common.domain.MasterStatus;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Edits a prep station. Making it the default clears the flag on the branch's other stations. */
public record UpdatePrepStationRequest(
        @NotBlank @Size(max = 60) String name,
        @Size(max = 255) String printerHost,
        @Min(1) @Max(65535) Integer printerPort,
        boolean defaultStation,
        MasterStatus status
) {}
