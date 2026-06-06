package com.erp.platform.audit;

/**
 * Append-only audit trail service (ADR-0004 D-2). All methods join the caller's active
 * transaction ({@code MANDATORY}) so the audit INSERT commits or rolls back atomically with the
 * business change — structural same-TX guarantee, not a discipline rule.
 *
 * <p>No update, delete, or remove methods are declared; the interface is intentionally narrow
 * (ADR-0004 D-5 — append-only enforced in application).
 */
public interface AuditService {

    /**
     * Records an audit event for an authenticated call. Actor, company, branch, and timestamp
     * are resolved from {@link com.erp.platform.security.RequestContext} at record time (D-4).
     * IP is null until {@code RequestContext.Principal} gains the field (a later step).
     *
     * <p>Must be called inside an active transaction (propagation = MANDATORY).
     *
     * @param event the business fact to record
     */
    void record(AuditEvent event);

    /**
     * Records an audit event with an explicit actor and IP — for the unauthenticated login path
     * (ADR-0004 D-3) where there is no authenticated {@code RequestContext}. Company and branch
     * are null (no scope at login time).
     *
     * <p>Must be called inside an active transaction (propagation = MANDATORY).
     *
     * @param event       the business fact to record
     * @param actorUserId resolved user id, or {@code null} for unknown-username attempts
     * @param ip          client IP address from the HTTP request
     */
    void record(AuditEvent event, Long actorUserId, String ip);
}
