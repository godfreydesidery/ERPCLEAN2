package com.erp.platform.audit;

/**
 * Read shape for an audit row (ADR-0004 D-7). Internal ids are resolved to uids/usernames so the
 * wire carries only external identifiers (PROJECT-CONVENTIONS §3.3). {@code detail} is the raw JSON
 * string stored in the row; {@code at} is an ISO-8601 instant string.
 */
public record AuditLogDto(
        String actorUid,
        String actorUsername,
        String action,
        String targetType,
        String targetUid,
        String companyUid,
        String branchUid,
        String detail,
        String at,
        String ip) {
}
