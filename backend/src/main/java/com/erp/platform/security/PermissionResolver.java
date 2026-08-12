package com.erp.platform.security;

import com.erp.modules.iam.repository.UserRoleRepository;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Resolves the EFFECTIVE permission set for a caller in their active scope (ADR-0001 D-E). The JWT
 * never carries the permission set — branch switching changes the effective set without re-login —
 * so it is resolved per request from {@code user_role} (+ role permissions) for the active
 * (user, company, branch).
 *
 * <p>Super-admin ({@code is_root}) short-circuits to "allowed" and never hits the DB. For everyone
 * else the result is cached briefly per (user, company, branch); writes that change access
 * (grant/revoke, role-permission edits, branch assignment) call {@link #invalidateUser(Long)} or
 * {@link #invalidateRole(Long)} so a flip takes effect at once (risk R3 in the build plan). The
 * short TTL is a backstop if an invalidation is ever missed.
 *
 * <p><b>UAT wave 1 fix.</b> A user granted {@code SUPPLIER.MANAGE} kept getting 403 for roughly two
 * minutes even though {@code /auth/me} and the database both agreed he held it. Two defects:
 * <ol>
 *   <li>The branch-assignment write path ({@code UserBranchServiceImpl}) never invalidated at all.
 *   <li>The paths that DID invalidate did so <em>inside</em> the writing transaction, before commit.
 *       Any request that resolved in that window re-read the pre-commit state and re-cached the
 *       stale (denying) answer for a further full TTL — so the flip could be missed repeatedly, and
 *       each miss cost another 30 s. Invalidation is now ALSO replayed after the transaction
 *       completes, when the new grant is actually visible to other connections.
 * </ol>
 * Invalidation is targeted at the affected user (and, for a role edit, at every current holder of
 * that role) rather than clearing the whole cache: a company-wide {@code cache.clear()} on every
 * IAM write throws away every other user's resolved set and sends the whole tenant back to the
 * database, which is exactly the wrong behaviour on a busy morning. {@link #invalidate()} is kept
 * for tests and for the rare write whose blast radius genuinely is "everyone".
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
     * Whether the caller is a provisioned, scoped MEMBER of their active company — i.e. they hold at
     * least one role (any permission) in the active (company, branch) scope. Root is always a member.
     *
     * <p>This is the read-floor for low-sensitivity, company-scoped reference-data pickers (branch
     * list, product list, WHT-type list): a screen's branch/product picker must not hard-403 a user
     * who legitimately belongs to the company and holds the screen's primary verb but not the
     * supporting {@code *.VIEW} read (the role-grant read-closure gap from the 2026-06-28 sim,
     * ISSUE-001..006). It is NOT a tenant-isolation relaxation: membership is established from the
     * caller's OWN resolved grants in their OWN active company, and every list service still applies
     * its company-scope predicate downstream, so a member can only ever read their own company's data.
     */
    public boolean isMember(RequestContext.Principal principal, long nowMillis) {
        if (principal == null) {
            return false;
        }
        if (principal.root()) {
            return true;
        }
        return !resolve(principal.userId(), principal.companyId(), principal.branchId(), nowMillis)
                .isEmpty();
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

    // -------------------------------------------------------------------------
    // Invalidation
    // -------------------------------------------------------------------------

    /**
     * Drop every cached entry for one user, across all of their (company, branch) scopes. Call this
     * from any write that changes what THAT user may do — a role grant or revoke, a branch
     * assignment/removal, a change of default branch.
     *
     * <p>The eviction runs twice: once immediately (so the writing request itself sees the new
     * state) and once more after the surrounding transaction completes, because until commit the
     * new grant is invisible to every other connection and a concurrent read would otherwise
     * re-cache the stale answer for a further TTL.
     */
    public void invalidateUser(Long userId) {
        if (userId == null) {
            return;
        }
        evictUser(userId);
        replayAfterTransaction(() -> evictUser(userId));
    }

    /**
     * Drop the cached entries of every user who currently holds {@code roleId}. Call this when a
     * role's permission set changes or the role is archived — the grants are untouched but what
     * they confer is not.
     *
     * <p>The holder list is read NOW, inside the caller's transaction, so the deferred replay does
     * not need a database connection of its own after commit.
     */
    public void invalidateRole(Long roleId) {
        if (roleId == null) {
            return;
        }
        List<Long> holders = userRoles.findUserIdsByRoleId(roleId);
        if (holders.isEmpty()) {
            return;
        }
        holders.forEach(this::evictUser);
        replayAfterTransaction(() -> holders.forEach(this::evictUser));
    }

    /**
     * Drop the whole cache. Kept for tests and for writes whose blast radius really is every user;
     * prefer {@link #invalidateUser(Long)} / {@link #invalidateRole(Long)} so one IAM edit does not
     * push every signed-in user back to the database.
     */
    public void invalidate() {
        cache.clear();
    }

    /** Remove every (company, branch) entry cached for one user. */
    private void evictUser(Long userId) {
        String prefix = userId + ":";
        cache.keySet().removeIf(key -> key.startsWith(prefix));
    }

    /**
     * Run {@code action} again once the current transaction finishes, so the eviction lands when
     * the write is visible to other connections. Uses {@code afterCompletion} rather than
     * {@code afterCommit} deliberately: evicting after a ROLLBACK too is harmless (it costs one
     * cache miss) and it keeps the cache honest if the write half-failed. When no transaction is
     * active — a unit test, or a call outside a transactional service — there is nothing to wait
     * for and the immediate eviction already did the job.
     */
    private static void replayAfterTransaction(Runnable action) {
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCompletion(int status) {
                action.run();
            }
        });
    }
}
