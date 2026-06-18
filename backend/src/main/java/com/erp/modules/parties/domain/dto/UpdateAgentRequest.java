package com.erp.modules.parties.domain.dto;

import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.math.BigDecimal;

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
        Long appUserId,
        // --- P2 D5 performance targets (ADR-0041 D5) — all optional ---
        String country,
        BigDecimal salesTarget,
        BigDecimal quota
) {
    /**
     * Backward-compatible constructor for callers that predate the P2 D5 performance targets.
     * Defaults country, salesTarget and quota to null, so no existing call site changes.
     */
    public UpdateAgentRequest(
            PartyType partyType, String displayName, String legalName, String tin,
            Boolean vatRegistered, String vrn, String businessRegNo, String mobileMoneyNo,
            String phone, String email, String physicalAddress, String postalAddress, String region,
            String district, AgentKind agentKind, Long appUserId) {
        this(partyType, displayName, legalName, tin, vatRegistered, vrn, businessRegNo,
                mobileMoneyNo, phone, email, physicalAddress, postalAddress, region, district,
                agentKind, appUserId, null, null, null);
    }
}
