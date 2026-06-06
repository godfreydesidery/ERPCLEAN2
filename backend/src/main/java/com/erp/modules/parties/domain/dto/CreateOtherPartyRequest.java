package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.enums.PartyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request DTO to create a new OtherParty. */
public record CreateOtherPartyRequest(
        @NotNull Long companyId,
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
        String otherKind
) {
}
