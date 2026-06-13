package com.erp.modules.purchases.domain.dto;

import jakarta.validation.constraints.NotBlank;

/** Used for both approvePo and rejectPo (reason mandatory on reject). */
public record ApprovePoRequest(
        @NotBlank String companyUid,
        String reason
) {}
