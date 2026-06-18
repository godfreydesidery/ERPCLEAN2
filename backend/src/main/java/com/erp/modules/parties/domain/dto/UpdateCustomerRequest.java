package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.CustomerSegment;
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
        Long paymentTermsId,
        // --- P2 D5 / P2-mechanical master-data defaults (ADR-0041 D5) — all optional ---
        String country,
        Long defaultPriceListId,
        Long defaultAgentId,
        CustomerSegment segment,
        Boolean taxExempt,
        String taxExemptionRef,
        String defaultCurrency
) {
    /**
     * Backward-compatible constructor for callers that predate the P2 D5 master-data defaults.
     * Defaults country and all D5 fields to null, so no existing call site changes.
     */
    public UpdateCustomerRequest(
            PartyType partyType, String displayName, String legalName, String tin,
            Boolean vatRegistered, String vrn, String businessRegNo, String mobileMoneyNo,
            String phone, String email, String physicalAddress, String postalAddress, String region,
            String district, CustomerKind customerKind, MoneyDto creditLimit,
            Integer paymentTermsDays, Long paymentTermsId) {
        this(partyType, displayName, legalName, tin, vatRegistered, vrn, businessRegNo,
                mobileMoneyNo, phone, email, physicalAddress, postalAddress, region, district,
                customerKind, creditLimit, paymentTermsDays, paymentTermsId,
                null, null, null, null, null, null, null);
    }
}
