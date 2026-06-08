package com.erp.modules.routes.domain.dto;

import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.routes.domain.entity.RouteAgent;

/**
 * Enriched response DTO for a route ↔ agent assignment (ADR-0012 D-11).
 * Carries agent uid / code / name / agentKind and the is_primary flag.
 */
public record RouteAgentDto(
        Long routeId,
        Long agentId,
        String agentUid,
        String agentCode,
        String agentName,
        AgentKind agentKind,
        boolean isPrimary,
        String assignedAt,
        Long assignedBy
) {

    public static RouteAgentDto from(RouteAgent ra, String agentUid, String agentCode,
                                     String agentName, AgentKind agentKind) {
        return new RouteAgentDto(
                ra.getRoute().getId(),
                ra.getAgentId(),
                agentUid,
                agentCode,
                agentName,
                agentKind,
                ra.isPrimary(),
                ra.getAssignedAt() != null ? ra.getAssignedAt().toString() : null,
                ra.getAssignedBy()
        );
    }
}
