package com.erp.modules.parties.domain.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for creating a party contact (ADR-0040 D-3).
 * Used for both customer and supplier contacts.
 */
public record CreatePartyContactRequest(
        @Size(max = 160)
        String contactPerson,

        @Size(max = 40)
        String phone,

        @Email
        @Size(max = 160)
        String email,

        boolean isPrimary
) {}
