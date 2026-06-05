package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Create-company request. {@code organisationUid} addresses the parent by its external id; the
 * service resolves it. Code is unique within the organisation (validated in the service).
 */
public record CreateCompanyRequest(
        @NotBlank @Size(max = 26) String organisationUid,
        @NotBlank @Size(max = 20) String code,
        @NotBlank @Size(max = 160) String name,
        @Size(max = 200) String legalName,
        @Size(max = 60) String taxId,
        @Size(max = 64) String timeZone) {
}
