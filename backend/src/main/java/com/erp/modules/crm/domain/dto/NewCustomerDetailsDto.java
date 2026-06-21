package com.erp.modules.crm.domain.dto;

import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Contact details for promoting a lead to a new Parties customer (ADR-0031 D-3, FR-CRM-03).
 * Passed to CustomerService.create; CRM does not write the customers table directly.
 *
 * <p>{@code partyType} defaults to {@link PartyType#INDIVIDUAL} when null (fixes crm-062:
 * walk-in/cash leads must not be forced through BUSINESS validation which requires a TIN).
 * Callers that explicitly supply {@link PartyType#BUSINESS} must also supply a TIN through
 * CustomerService's own validation — CRM does not re-check BR-PARTY-04 here.
 */
public record NewCustomerDetailsDto(
        @NotBlank String displayName,
        @NotNull CustomerKind customerKind,
        /**
         * Optional: INDIVIDUAL or BUSINESS. Defaults to INDIVIDUAL (BR-PARTY-04 safe for walk-ins).
         * Callers promoting a registered business customer should pass BUSINESS explicitly.
         */
        PartyType partyType,
        String phone,
        String email,
        String physicalAddress,
        String region,
        String district
) {}
