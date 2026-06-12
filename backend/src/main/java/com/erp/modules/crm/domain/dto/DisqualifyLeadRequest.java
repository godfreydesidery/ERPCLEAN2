package com.erp.modules.crm.domain.dto;

import jakarta.validation.constraints.NotBlank;

public record DisqualifyLeadRequest(
        @NotBlank String reason
) {}
