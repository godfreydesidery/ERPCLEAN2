package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request DTO to create a new Customer. Numeric FK fields are Long (Jackson accepts 42 or "42"). */
public record CreateCustomerRequest(
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
        @NotNull CustomerKind customerKind,
        MoneyDto creditLimit,
        Integer paymentTermsDays,
        Long paymentTermsId
) {
}
