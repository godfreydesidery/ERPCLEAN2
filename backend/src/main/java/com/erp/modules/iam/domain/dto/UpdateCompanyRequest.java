package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Update mutable company fields. Code/organisation are not changed here (identity-ish). */
public record UpdateCompanyRequest(
        @NotBlank @Size(max = 160) String name,
        @Size(max = 200) String legalName,
        @Size(max = 60) String taxId,
        @Size(max = 64) String timeZone) {
}
