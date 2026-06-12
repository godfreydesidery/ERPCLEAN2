package com.erp.modules.crm.domain.dto;

import com.erp.modules.crm.domain.enums.LeadSource;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public record CreateLeadRequest(
        @NotNull Long companyId,
        @NotBlank String displayName,
        @NotNull LeadSource leadSource,
        String companyName,
        String contactPerson,
        String phone,
        String email,
        Long ownerUserId,
        String notes
) {}
