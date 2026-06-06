package com.erp.platform.security;

import java.io.Serializable;
import org.springframework.security.access.PermissionEvaluator;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

/**
 * Backs SpEL {@code hasPermission(...)} in {@code @PreAuthorize} (ADR-0002). Two forms:
 *
 * <ul>
 *   <li><b>1-arg</b> {@code hasPermission('CODE')} → {@link #hasPermission(Authentication, Object,
 *       Object)} with a null target: the caller holds {@code CODE} in their active scope. For
 *       create/list, where the scope is the active company implicitly.</li>
 *   <li><b>2-arg</b> {@code hasPermission(#uid, 'type', 'CODE')} → {@link #hasPermission(
 *       Authentication, Serializable, String, Object)}: the caller holds {@code CODE} AND the
 *       target {@code uid} (of {@code type} company/branch) lives in the caller's active company.
 *       Closes the cross-company hole (ADR-0002 BLOCKER).</li>
 * </ul>
 *
 * <p>The principal comes from {@link RequestContext} (populated by the JWT filter), not from the
 * {@link Authentication}, so the same active company/branch the rest of the request sees is used.
 * Both forms fail closed: no principal, no code, or an unresolvable target → false.
 */
@Component
public class ErpPermissionEvaluator implements PermissionEvaluator {

    private final PermissionResolver permissions;
    private final ScopeGuard scopeGuard;

    public ErpPermissionEvaluator(PermissionResolver permissions, ScopeGuard scopeGuard) {
        this.permissions = permissions;
        this.scopeGuard = scopeGuard;
    }

    /** 1-arg form: {@code hasPermission('CODE')} — permission only, scope is the active context. */
    @Override
    public boolean hasPermission(Authentication authentication, Object permission, Object unused) {
        // Spring routes hasPermission('CODE') here with (targetDomainObject='CODE', permission=null).
        // We treat the first non-null of the two as the code so either arrangement works.
        String code = asString(permission != null ? permission : unused);
        RequestContext.Principal principal = RequestContext.get();
        return permissions.hasPermission(principal, code, System.currentTimeMillis());
    }

    /** 2-arg form: {@code hasPermission(#uid, 'type', 'CODE')} — permission AND same-company scope. */
    @Override
    public boolean hasPermission(Authentication authentication, Serializable targetId,
                                 String targetType, Object permission) {
        RequestContext.Principal principal = RequestContext.get();
        String code = asString(permission);
        if (!permissions.hasPermission(principal, code, System.currentTimeMillis())) {
            return false;
        }
        // Root is already allowed by the permission check's short-circuit; for non-root the target
        // must live in the caller's active company.
        if (principal != null && principal.root()) {
            return true;
        }
        return scopeGuard.canActOn(principal, targetType, asString(targetId));
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
