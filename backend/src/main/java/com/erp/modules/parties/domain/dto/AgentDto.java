package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.platform.common.domain.MasterStatus;
import java.math.BigDecimal;

/** Response DTO for an Agent (ADR-0006 D-11). */
public record AgentDto(
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
        String country,
        AgentKind agentKind,
        Long appUserId,
        BigDecimal salesTarget,
        BigDecimal quota,
        MasterStatus status,
        Long version,
        String createdAt,
        Long createdBy,
        String updatedAt,
        Long updatedBy
) {

    public static AgentDto from(Agent a) {
        return new AgentDto(
                a.getId(),
                a.getUid(),
                a.getCompanyId(),
                a.getCode(),
                a.getPartyType(),
                a.getDisplayName(),
                a.getLegalName(),
                a.getTin(),
                a.isVatRegistered(),
                a.getVrn(),
                a.getBusinessRegNo(),
                a.getMobileMoneyNo(),
                a.getPhone(),
                a.getEmail(),
                a.getPhysicalAddress(),
                a.getPostalAddress(),
                a.getRegion(),
                a.getDistrict(),
                a.getCountry(),
                a.getAgentKind(),
                a.getAppUserId(),
                a.getSalesTarget(),
                a.getQuota(),
                a.getStatus(),
                a.getVersion(),
                a.getCreatedAt() != null ? a.getCreatedAt().toString() : null,
                a.getCreatedBy(),
                a.getUpdatedAt() != null ? a.getUpdatedAt().toString() : null,
                a.getUpdatedBy()
        );
    }
}
