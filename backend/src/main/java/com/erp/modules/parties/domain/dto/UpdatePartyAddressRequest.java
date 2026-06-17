package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.enums.AddressRole;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * Request DTO for updating a party address (ADR-0040 D-3).
 */
public record UpdatePartyAddressRequest(
        @NotNull
        AddressRole addressRole,

        boolean isDefault,

        @Size(max = 2)
        String country,

        @Size(max = 200)
        String addressLine1,

        @Size(max = 200)
        String addressLine2,

        @Size(max = 100)
        String city,

        @Size(max = 80)
        String region,

        @Size(max = 20)
        String postalCode
) {}
