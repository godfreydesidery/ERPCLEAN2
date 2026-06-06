package com.erp.platform.security;

import com.erp.modules.iam.repository.UserRoleRepository;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Resolves the EFFECTIVE permission set for a caller in their active scope (ADR-0001 D-E). The JWT
 * never carries the permission set — branch switching changes the effective set without re-login —
 * so it is resolved per request from {@code user_role} (+ role permissions) for the active
 * (user, company, branch).
 *
 * <p>Super-admin ({@code is_root}) short-circuits to "allowed" and never hits the DB. For everyone
 * else the result is cached briefly per (user, company, branch); writes that change access
 * (grant/revoke, role-permission edits) call {@link #invalidate()} so a flip takes effect at once
 * (risk R3 in the build plan). The short TTL is a backstop if an invalidation is ever missed.
 */
@Component
public class PermissionResolver {

    private static final Logger log = LoggerFactory.getLogger(PermissionResolver.class);

    /** Short TTL backstop — long enough to absorb a request burst, short enough to self-heal. */
    private static final long TTL_MILLIS = Duration.ofSeconds(30).toMillis();

    private final UserRoleRepository userRoles;
    private final ConcurrentHashMap<String, Entry> cache = new ConcurrentHashMap<>();

    public PermissionResolver(UserRoleRepository userRoles) {
        this.userRoles = userRoles;
    }

    private record Entry(Set<String> codes, long expiresAt) {
    }

    /**
     * Effective permission codes for the caller in their active scope. Root is handled by the
     * caller's {@code isRoot} short-circuit in {@link #hasPermission}; this only resolves real grants.
     */
    public Set<String> resolve(Long userId, Long companyId, Long branchId, long nowMillis) {
        if (userId == null || companyId == null) {
            // No active company scope (e.g. a user with no branch) → no resolved permissions.
            return Set.of();
        }
        String key = userId + ":" + companyId + ":" + branchId;
        Entry cached = cache.get(key);
        if (cached != null && cached.expiresAt() > nowMillis) {
            return cached.codes();
        }
        Set<String> codes = Set.copyOf(userRoles.resolvePermissionCodes(userId, companyId, branchId));
        cache.put(key, new Entry(codes, nowMillis + TTL_MILLIS));
        return codes;
    }

    /**
     * Whether the caller may exercise {@code permissionCode} in their active scope. Root is always
     * allowed (and audited elsewhere); otherwise the code must be in the resolved set.
     */
    public boolean hasPermission(RequestContext.Principal principal, String permissionCode, long nowMillis) {
        if (principal == null) {
            return false;
        }
        if (principal.root()) {
            // Root bypasses scoping (ADR-0001 D-E). The AUDIT trail of root activity is the
            // audit_log: every root ACTION is recorded by its own action row (actor=root), and a
            // distinct ROOT.BYPASS row is written when root acts cross-company (ADR-0004 D-9). This
            // per-check line is DEBUG-only observability — not the audit record — so it can't flood.
            log.debug("ROOT bypass: user={} (id={}) '{}' in company={} branch={}",
                    principal.username(), principal.userId(), permissionCode,
                    principal.companyId(), principal.branchId());
            return true;
        }
        return resolve(principal.userId(), principal.companyId(), principal.branchId(), nowMillis)
                .contains(permissionCode);
    }

    /** Drop the whole cache on any access-changing write (grant/revoke, role-permission edit). */
    public void invalidate() {
        cache.clear();
    }
}
