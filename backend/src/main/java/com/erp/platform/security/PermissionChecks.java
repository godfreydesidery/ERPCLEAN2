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

    /**
     * Read-floor gate for low-sensitivity, company-scoped reference-data LIST/PICKER endpoints
     * (product list, WHT-type list): allow if the caller holds {@code code} OR is a provisioned,
     * scoped member of their active company (root counts as a member).
     *
     * <p>Why: operational roles are hand-curated subsets that frequently omit a screen's supporting
     * {@code *.VIEW} read-dependency, so the picker hard-403s and blanks an otherwise-authorised
     * screen (2026-06-28 sim, ISSUE-001..006). This widens a READ only, never a write, and does NOT
     * weaken tenant isolation: the list service still applies its own company-scope predicate
     * ({@code ScopeGuard.assertCanActIn}) on the {@code companyId} argument, so a member can only ever
     * read their own company's reference data. The endpoint remains permission-gated (a non-member /
     * unauthenticated caller is still refused), satisfying the deny-by-default coverage gate.
     */
    public boolean hasOrMember(String code) {
        RequestContext.Principal principal = RequestContext.get();
        long now = System.currentTimeMillis();
        return permissionResolver.hasPermission(principal, code, now)
                || permissionResolver.isMember(principal, now);
    }

    /**
     * Company-scoped read-floor for a reference-data LIST/PICKER addressed by a company uid (the
     * branch list): allow if the caller holds {@code code} for the target, OR is a scoped member who
     * may act on the target company. Unlike {@link #hasOrMember(String)} the company-scope predicate
     * lives HERE (the branch list service has no separate {@code assertCanActIn}), so the member path
     * still goes through {@link ScopeGuard#canActOn} — a member can list branches of their OWN company
     * only, never another tenant's. Read-widening only; the endpoint stays gated and fails closed for
     * a non-member or a cross-company target.
     */
    public boolean scopedOrMember(String uid, String targetType, String code) {
        if (scoped(uid, targetType, code)) {
            return true;
        }
        RequestContext.Principal principal = RequestContext.get();
        long now = System.currentTimeMillis();
        if (!permissionResolver.isMember(principal, now)) {
            return false;
        }
        // Member, but still must resolve to the caller's OWN company (root short-circuits inside).
        return principal != null && (principal.root() || scopeGuard.canActOn(principal, targetType, uid));
    }
}
