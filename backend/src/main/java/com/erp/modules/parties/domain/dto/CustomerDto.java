package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.platform.common.domain.MasterStatus;

/**
 * Response DTO for a Customer (ADR-0006 D-11). Carries both {@code id} (JSON string via global
 * Long-as-string config) and {@code uid} for URL addressing. Money fields follow ADR-0005 D-7.
 */
public record CustomerDto(
        Long id,
        String uid,
        Long companyId,
        String code,
        PartyType partyType,
        String displayName,
        String legalName,
        String tin,
        boolean vatRegistered,
        String vrn,
        String businessRegNo,
        String mobileMoneyNo,
        String phone,
        String email,
        String physicalAddress,
        String postalAddress,
        String region,
        String district,
        CustomerKind customerKind,
        MoneyDto creditLimit,
        Integer paymentTermsDays,
        Long paymentTermsId,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static CustomerDto from(Customer c) {
        return new CustomerDto(
                c.getId(),
                c.getUid(),
                c.getCompanyId(),
                c.getCode(),
                c.getPartyType(),
                c.getDisplayName(),
                c.getLegalName(),
                c.getTin(),
                c.isVatRegistered(),
                c.getVrn(),
                c.getBusinessRegNo(),
                c.getMobileMoneyNo(),
                c.getPhone(),
                c.getEmail(),
                c.getPhysicalAddress(),
                c.getPostalAddress(),
                c.getRegion(),
                c.getDistrict(),
                c.getCustomerKind(),
                MoneyDto.from(c.getCreditLimit()),
                c.getPaymentTermsDays(),
                c.getPaymentTermsId(),
                c.getStatus(),
                c.getVersion(),
                c.getCreatedAt() != null ? c.getCreatedAt().toString() : null,
                c.getCreatedBy(),
                c.getUpdatedAt() != null ? c.getUpdatedAt().toString() : null,
                c.getUpdatedBy()
        );
    }
}
