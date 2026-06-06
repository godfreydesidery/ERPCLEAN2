package com.erp.modules.parties.domain.dto;

import java.time.Instant;

/**
 * Branch association summary returned by branch-list endpoints. {@code assignedAt} is an ISO-8601
 * instant string (timestamps cross the wire as strings, per the UserDto/AuditLogDto convention).
 */
public record PartyBranchDto(Long branchId, String assignedAt, Long assignedBy) {

    /** Build from raw association fields, formatting the instant null-safely. */
    public static PartyBranchDto of(Long branchId, Instant assignedAt, Long assignedBy) {
        return new PartyBranchDto(branchId, assignedAt != null ? assignedAt.toString() : null, assignedBy);
    }
}
