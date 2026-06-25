package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Explicitly assign a user to a company (V77, USER.COMPANY.MANAGE). Both uids are required;
 * the caller typically already knows the user uid (from the user-admin UI) and picks the company
 * from the company picker.
 */
public record AssignUserCompanyRequest(
        @NotBlank @Size(max = 26) String userUid,
        @NotBlank @Size(max = 26) String companyUid) {
}
