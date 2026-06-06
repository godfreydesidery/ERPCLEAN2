package com.erp.platform.audit;

import java.util.Map;

/**
 * Immutable carrier built at the call site and handed to {@link AuditService#record}.
 * Carries only the business fact (action, target, detail); actor, scope, and IP are resolved
 * from {@link com.erp.platform.security.RequestContext} inside the service at record time (D-4).
 *
 * <p>Use the static factory for the common authenticated path:
 * <pre>{@code
 *   AuditEvent.of("USER.CREATE", "app_users", user.getId(), user.getUid())
 *             .detail(Map.of("username", username))
 * }</pre>
 */
public final class AuditEvent {

    private final String action;
    private final String targetType;
    private final Long targetId;
    private final String targetUid;
    private Map<String, Object> detail;

    private AuditEvent(String action, String targetType, Long targetId, String targetUid) {
        this.action     = action;
        this.targetType = targetType;
        this.targetId   = targetId;
        this.targetUid  = targetUid;
    }

    // -------------------------------------------------------------------------
    // Static factories
    // -------------------------------------------------------------------------

    /** Full target reference (id + uid). */
    public static AuditEvent of(String action, String targetType, Long targetId, String targetUid) {
        return new AuditEvent(action, targetType, targetId, targetUid);
    }

    /** Convenience for actions without a single target row (e.g. {@code LOGIN.FAIL}). */
    public static AuditEvent of(String action, String targetType) {
        return new AuditEvent(action, targetType, null, null);
    }

    // -------------------------------------------------------------------------
    // Fluent builder
    // -------------------------------------------------------------------------

    /** Attaches a detail map; returns {@code this} for fluent chaining. */
    public AuditEvent detail(Map<String, Object> detail) {
        this.detail = detail;
        return this;
    }

    // -------------------------------------------------------------------------
    // Accessors
    // -------------------------------------------------------------------------

    public String action()     { return action; }
    public String targetType() { return targetType; }
    public Long   targetId()   { return targetId; }
    public String targetUid()  { return targetUid; }

    /** May be {@code null} when no additional context is needed. */
    public Map<String, Object> detail() { return detail; }
}
