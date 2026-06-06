package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.entity.OtherParty;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.platform.common.domain.MasterStatus;

/** Response DTO for an OtherParty (ADR-0006 D-11). */
public record OtherPartyDto(
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
        String otherKind,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static OtherPartyDto from(OtherParty o) {
        return new OtherPartyDto(
                o.getId(),
                o.getUid(),
                o.getCompanyId(),
                o.getCode(),
                o.getPartyType(),
                o.getDisplayName(),
                o.getLegalName(),
                o.getTin(),
                o.isVatRegistered(),
                o.getVrn(),
                o.getBusinessRegNo(),
                o.getMobileMoneyNo(),
                o.getPhone(),
                o.getEmail(),
                o.getPhysicalAddress(),
                o.getPostalAddress(),
                o.getRegion(),
                o.getDistrict(),
                o.getOtherKind(),
                o.getStatus(),
                o.getVersion(),
                o.getCreatedAt() != null ? o.getCreatedAt().toString() : null,
                o.getCreatedBy(),
                o.getUpdatedAt() != null ? o.getUpdatedAt().toString() : null,
                o.getUpdatedBy()
        );
    }
}
