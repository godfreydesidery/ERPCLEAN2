package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Request DTO to update an Agent. agentKind changes are allowed (service re-validates user link). */
public record UpdateAgentRequest(
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
        @NotNull AgentKind agentKind,
        Long appUserId
) {
}
