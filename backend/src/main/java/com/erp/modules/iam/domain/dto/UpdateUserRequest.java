package com.erp.modules.iam.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Thin update request: display / contact fields only. Username, password, status and isRoot are
 * managed via dedicated endpoints or are immutable via this API.
 */
public record UpdateUserRequest(
        @NotBlank @Size(max = 160) String displayName,
        @Size(max = 160) @Email String email,
        @Size(max = 40) String phone) {
}
