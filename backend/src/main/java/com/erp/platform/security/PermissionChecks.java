package com.erp.platform.security;

import org.springframework.stereotype.Component;

/**
 * Bean-expression gate for {@code @PreAuthorize} (ADR-0002, Bug-1 fix). Replaces the removed
 * {@code ErpPermissionEvaluator} / {@code hasPermission(...)} SpEL form (which has no 1-arg
 * overload in Spring Security) with {@code @perm.has(...)} and {@code @perm.scoped(...)}.
 *
 * <p>All logic is delegated to the already-tested {@link PermissionResolver} and
 * {@link ScopeGuard}; this class is pure wiring.
 */
@Component("perm")
public class PermissionChecks {

    private final PermissionResolver permissionResolver;
    private final ScopeGuard scopeGuard;

    public PermissionChecks(PermissionResolver permissionResolver, ScopeGuard scopeGuard) {
        this.permissionResolver = permissionResolver;
        this.scopeGuard = scopeGuard;
    }

    /**
     * True if the current principal holds {@code code} in their active scope.
     * Used for create/list operations where there is no target uid to scope-check.
     */
    public boolean has(String code) {
        return permissionResolver.hasPermission(RequestContext.get(), code, System.currentTimeMillis());
    }

    /**
     * True if the principal holds {@code code} AND may act on the target (root, or target lives in
     * the principal's active company). Used for target-uid path operations and body-scoped list/create
     * where a companyUid is available.
     */
    public boolean scoped(String uid, String targetType, String code) {
        RequestContext.Principal principal = RequestContext.get();
        if (!permissionResolver.hasPermission(principal, code, System.currentTimeMillis())) {
            return false;
        }
        // Root is already granted by hasPermission's short-circuit above, but ScopeGuard.canActOn
        // also short-circuits for root, so calling it is safe and keeps the logic centralised.
        return principal != null && (principal.root() || scopeGuard.canActOn(principal, targetType, uid));
    }
}
