package com.erp.modules.parties.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating a party contact (ADR-0040 D-3).
 */
public record UpdatePartyContactRequest(
        @Size(max = 160)
        String contactPerson,

        @Size(max = 40)
        String phone,

        @Email
        @Size(max = 160)
        String email,

        boolean isPrimary
) {}
