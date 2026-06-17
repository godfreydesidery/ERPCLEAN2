package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request DTO to update a Customer. {@code companyId} and {@code code} are immutable — not here. */
public record UpdateCustomerRequest(
        @NotNull PartyType partyType,
        @NotBlank String displayName,
        String legalName,
        String tin,
        Boolean vatRegistered,
        String vrn,
        String businessRegNo,
        String mobileMoneyNo,
        String phone,
        String email,
        String physicalAddress,
        String postalAddress,
        String region,
        String district,
        @NotNull CustomerKind customerKind,
        MoneyDto creditLimit,
        Integer paymentTermsDays,
        Long paymentTermsId
) {
}
