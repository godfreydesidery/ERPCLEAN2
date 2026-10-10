package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

/**
 * Request DTO to update a Supplier.
 *
 * <p>AP-10: {@code paymentTermsDays}, {@code paymentTermsId}, {@code country},
 * {@code defaultCurrency}, {@code leadTimeDays}, {@code minOrderValue} and {@code defaultWhtTypeId}
 * are "null = unchanged". {@code paymentTermsId} / {@code defaultWhtTypeId} = 0 clears the link.
 */
public record UpdateSupplierRequest(
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
        @NotNull SupplierKind supplierKind,
        Integer paymentTermsDays,
        Long paymentTermsId,
        // --- P2 D5 master-data defaults (ADR-0041 D5) — all optional ---
        String country,
        String defaultCurrency,
        Integer leadTimeDays,
        BigDecimal minOrderValue,
        Long defaultWhtTypeId
) {
    /**
     * Backward-compatible constructor for callers that predate the P2 D5 master-data defaults.
     * Defaults country and all D5 fields to null, so no existing call site changes.
     */
    public UpdateSupplierRequest(
            PartyType partyType, String displayName, String legalName, String tin,
            Boolean vatRegistered, String vrn, String businessRegNo, String mobileMoneyNo,
            String phone, String email, String physicalAddress, String postalAddress, String region,
            String district, SupplierKind supplierKind, Integer paymentTermsDays, Long paymentTermsId) {
        this(partyType, displayName, legalName, tin, vatRegistered, vrn, businessRegNo,
                mobileMoneyNo, phone, email, physicalAddress, postalAddress, region, district,
                supplierKind, paymentTermsDays, paymentTermsId,
                null, null, null, null, null);
    }
}
