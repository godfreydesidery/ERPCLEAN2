package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.CustomerSegment;
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
     * Defaults country and all D5 fields to null (the service keeps the entity defaults), so no
     * existing call site changes (mirrors {@code JournalEntryDraft.LineDraft} convenience ctor).
     */
    public CreateCustomerRequest(
            Long companyId, PartyType partyType, String displayName, String legalName, String tin,
            Boolean vatRegistered, String vrn, String businessRegNo, String mobileMoneyNo,
            String phone, String email, String physicalAddress, String postalAddress, String region,
            String district, CustomerKind customerKind, MoneyDto creditLimit,
            Integer paymentTermsDays, Long paymentTermsId) {
        this(companyId, partyType, displayName, legalName, tin, vatRegistered, vrn, businessRegNo,
                mobileMoneyNo, phone, email, physicalAddress, postalAddress, region, district,
                customerKind, creditLimit, paymentTermsDays, paymentTermsId,
                null, null, null, null, null, null, null);
    }
}
