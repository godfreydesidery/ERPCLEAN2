package com.erp.modules.crm.domain.dto;

import com.erp.modules.parties.domain.enums.CustomerKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Contact details for promoting a lead to a new Parties customer (ADR-0031 D-3, FR-CRM-03).
 * Passed to CustomerService.create; CRM does not write the customers table directly.
 */
public record NewCustomerDetailsDto(
        @NotBlank String displayName,
        @NotNull CustomerKind customerKind,
        String phone,
        String email,
        String physicalAddress,
        String region,
        String district
) {}
