package com.erp.modules.crm.domain.dto;

/**
 * Request to qualify a lead (ADR-0031 D-3, FR-CRM-03).
 * Exactly one of existingCustomerUid or newCustomerDetails must be provided.
 */
public record QualifyLeadRequest(
        /** UID of an existing Parties customer to link. Null if promoting a new one. */
        String existingCustomerUid,
        /** Details for a new customer to promote via CustomerService.create. Null if linking. */
        NewCustomerDetailsDto newCustomerDetails
) {}
