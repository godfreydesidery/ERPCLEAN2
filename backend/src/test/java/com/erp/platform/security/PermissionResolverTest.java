package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.UserRoleRepository;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Unit tests for {@link PermissionResolver}'s cache invalidation (UAT wave 1).
 *
 * <p>The defect: a user granted {@code SUPPLIER.MANAGE} kept receiving 403 for about two minutes,
 * while {@code /auth/me} and the database both said he held it. The permission cache has a ~30 s
 * TTL, the branch-assignment write path never invalidated at all, and the paths that did invalidate
 * did so before their transaction committed — so a concurrent read could re-cache the stale denying
 * answer for another full TTL, repeatedly.
 *
 * <p>{@code NOW} is pinned throughout so the TTL never expires on its own: anything that becomes
 * visible in these tests became visible because invalidation worked, not because time passed.
 */
class PermissionResolverTest {

    private static final long NOW = 1_000_000L;

    private static final Long USER    = 7L;
    private static final Long OTHER   = 8L;
    private static final Long COMPANY = 100L;
    private static final Long BRANCH  = 200L;
    private static final Long ROLE    = 42L;

    private static final String CODE = "SUPPLIER.MANAGE";

    private UserRoleRepository userRoles;
    private PermissionResolver resolver;

    private RequestContext.Principal principal(Long userId) {
        return new RequestContext.Principal(userId, "user" + userId, false, COMPANY, BRANCH, null);
    }

    @BeforeEach
    void setUp() {
        userRoles = mock(UserRoleRepository.class);
        resolver = new PermissionResolver(userRoles);
        // Nobody holds anything until a test says otherwise.
        when(userRoles.resolvePermissionCodes(any(), any(), any())).thenReturn(List.of());
    }

    @AfterEach
    void tearDown() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    /** Simulates the DB row a grant would have written. */
    private void databaseGrants(Long userId, String... codes) {
        when(userRoles.resolvePermissionCodes(userId, COMPANY, BRANCH)).thenReturn(List.of(codes));
    }

    // -----------------------------------------------------------------------
    // The headline case: granted, then effective on the very next request
    // -----------------------------------------------------------------------

    /**
     * The UAT reproduction. The user's screen 403s once (warming the cache with the denying answer),
     * the admin grants the permission, and the very next request must be allowed — no waiting for
     * the TTL, no re-login.
     */
    @Test
    void freshlyGrantedPermission_isEffectiveOnTheVeryNextRequest() {
        assertThat(resolver.hasPermission(principal(USER), CODE, NOW))
                .as("denied before the grant — this caches the negative answer")
                .isFalse();

        databaseGrants(USER, CODE);
        resolver.invalidateUser(USER);

        assertThat(resolver.hasPermission(principal(USER), CODE, NOW))
                .as("effective immediately at the same instant: only invalidation can explain it")
                .isTrue();
    }

    /** Without invalidation the caller is stuck on the stale answer — the behaviour UAT reported. */
    @Test
    void withoutInvalidation_theStaleDenialSurvives() {
        assertThat(resolver.hasPermission(principal(USER), CODE, NOW)).isFalse();

        databaseGrants(USER, CODE);

        assertThat(resolver.hasPermission(principal(USER), CODE, NOW))
                .as("this is the bug: the DB agrees, the cache does not")
                .isFalse();
    }

    /** A revoke must take effect just as promptly — the dangerous direction. */
    @Test
    void revokedPermission_isGoneOnTheVeryNextRequest() {
        databaseGrants(USER, CODE);
        assertThat(resolver.hasPermission(principal(USER), CODE, NOW)).isTrue();

        when(userRoles.resolvePermissionCodes(USER, COMPANY, BRANCH)).thenReturn(List.of());
        resolver.invalidateUser(USER);

        assertThat(resolver.hasPermission(principal(USER), CODE, NOW)).isFalse();
    }

    // -----------------------------------------------------------------------
    // Targeted, not a whole-cache nuke
    // -----------------------------------------------------------------------

    /** One user's grant must not evict every other signed-in user's resolved set. */
    @Test
    void invalidateUser_leavesOtherUsersCacheEntriesIntact() {
        databaseGrants(OTHER, CODE);
        assertThat(resolver.hasPermission(principal(OTHER), CODE, NOW))
                .as("other user resolved and cached")
                .isTrue();

        resolver.invalidateUser(USER);

        // If OTHER had been evicted too, this re-read would return the (now empty) stub below.
        when(userRoles.resolvePermissionCodes(OTHER, COMPANY, BRANCH)).thenReturn(List.of());
        assertThat(resolver.hasPermission(principal(OTHER), CODE, NOW))
                .as("still served from cache — the other user was not disturbed")
                .isTrue();
    }

    /** Key eviction is by exact user prefix: user 7 must not evict user 77. */
    @Test
    void invalidateUser_doesNotEvictAUserWhoseIdSharesAPrefix() {
        Long prefixSibling = 77L;
        databaseGrants(prefixSibling, CODE);
        assertThat(resolver.hasPermission(principal(prefixSibling), CODE, NOW)).isTrue();

        resolver.invalidateUser(7L);

        when(userRoles.resolvePermissionCodes(prefixSibling, COMPANY, BRANCH)).thenReturn(List.of());
        assertThat(resolver.hasPermission(principal(prefixSibling), CODE, NOW))
                .as("77 is not 7 — the cached entry must survive")
                .isTrue();
    }

    /** A user's entries in every scope go, not just the one that happens to be active. */
    @Test
    void invalidateUser_evictsEveryScopeOfThatUser() {
        Long otherBranch = 201L;
        when(userRoles.resolvePermissionCodes(USER, COMPANY, BRANCH)).thenReturn(List.of(CODE));
        when(userRoles.resolvePermissionCodes(USER, COMPANY, otherBranch)).thenReturn(List.of(CODE));

        assertThat(resolver.resolve(USER, COMPANY, BRANCH, NOW)).contains(CODE);
        assertThat(resolver.resolve(USER, COMPANY, otherBranch, NOW)).contains(CODE);

        when(userRoles.resolvePermissionCodes(USER, COMPANY, BRANCH)).thenReturn(List.of());
        when(userRoles.resolvePermissionCodes(USER, COMPANY, otherBranch)).thenReturn(List.of());
        resolver.invalidateUser(USER);

        assertThat(resolver.resolve(USER, COMPANY, BRANCH, NOW)).isEmpty();
        assertThat(resolver.resolve(USER, COMPANY, otherBranch, NOW)).isEmpty();
    }

    // -----------------------------------------------------------------------
    // Role-permission edits reach every holder
    // -----------------------------------------------------------------------

    @Test
    void invalidateRole_evictsEveryCurrentHolderOfThatRole() {
        when(userRoles.findUserIdsByRoleId(ROLE)).thenReturn(List.of(USER, OTHER));
        databaseGrants(USER, CODE);
        databaseGrants(OTHER, CODE);
        assertThat(resolver.resolve(USER, COMPANY, BRANCH, NOW)).contains(CODE);
        assertThat(resolver.resolve(OTHER, COMPANY, BRANCH, NOW)).contains(CODE);

        // The role loses the permission; both holders must see that on their next request.
        when(userRoles.resolvePermissionCodes(USER, COMPANY, BRANCH)).thenReturn(List.of());
        when(userRoles.resolvePermissionCodes(OTHER, COMPANY, BRANCH)).thenReturn(List.of());
        resolver.invalidateRole(ROLE);

        assertThat(resolver.resolve(USER, COMPANY, BRANCH, NOW)).isEmpty();
        assertThat(resolver.resolve(OTHER, COMPANY, BRANCH, NOW)).isEmpty();
    }

    @Test
    void invalidateRole_withNoHolders_touchesNothing() {
        when(userRoles.findUserIdsByRoleId(ROLE)).thenReturn(List.of());
        databaseGrants(USER, CODE);
        assertThat(resolver.resolve(USER, COMPANY, BRANCH, NOW)).contains(CODE);

        resolver.invalidateRole(ROLE);

        when(userRoles.resolvePermissionCodes(USER, COMPANY, BRANCH)).thenReturn(List.of());
        assertThat(resolver.resolve(USER, COMPANY, BRANCH, NOW))
                .as("nobody holds the role, so nobody's cache should have been dropped")
                .contains(CODE);
    }

    // -----------------------------------------------------------------------
    // The pre-commit race: invalidation is replayed after the transaction ends
    // -----------------------------------------------------------------------

    /**
     * The two-minute symptom. Inside the writing transaction the new grant is invisible to other
     * connections, so a request arriving between {@code invalidate} and {@code commit} re-caches the
     * denying answer — and, before this fix, that stale entry then lived a further full TTL. The
     * replay registered on the transaction evicts it again once the write is actually visible.
     */
    @Test
    void invalidationIsReplayedAfterTheTransactionCompletes() {
        TransactionSynchronizationManager.initSynchronization();

        // A request warms the cache with the pre-grant (denying) answer.
        assertThat(resolver.hasPermission(principal(USER), CODE, NOW)).isFalse();

        // The grant is written and invalidates — still inside its transaction.
        resolver.invalidateUser(USER);

        // A concurrent request lands before commit: it still cannot see the new row, so it
        // re-caches the stale denial.
        assertThat(resolver.hasPermission(principal(USER), CODE, NOW)).isFalse();

        // Commit: the row is now visible, and the registered replay drops the stale entry.
        databaseGrants(USER, CODE);
        List<TransactionSynchronization> registered =
                TransactionSynchronizationManager.getSynchronizations();
        assertThat(registered).as("an after-transaction replay must have been registered").hasSize(1);
        registered.forEach(s -> s.afterCompletion(TransactionSynchronization.STATUS_COMMITTED));

        assertThat(resolver.hasPermission(principal(USER), CODE, NOW))
                .as("allowed at the same instant once the write is visible")
                .isTrue();
    }

    /** Outside a transaction (no synchronization active) invalidation must still work, and not blow up. */
    @Test
    void invalidateUser_withNoActiveTransaction_stillEvicts() {
        assertThat(TransactionSynchronizationManager.isSynchronizationActive()).isFalse();
        assertThat(resolver.hasPermission(principal(USER), CODE, NOW)).isFalse();

        databaseGrants(USER, CODE);
        resolver.invalidateUser(USER);

        assertThat(resolver.hasPermission(principal(USER), CODE, NOW)).isTrue();
    }

    @Test
    void invalidateUser_withNullUserId_isANoOp() {
        databaseGrants(USER, CODE);
        assertThat(resolver.resolve(USER, COMPANY, BRANCH, NOW)).contains(CODE);

        resolver.invalidateUser(null);

        when(userRoles.resolvePermissionCodes(USER, COMPANY, BRANCH)).thenReturn(List.of());
        assertThat(resolver.resolve(USER, COMPANY, BRANCH, NOW)).contains(CODE);
    }

    // -----------------------------------------------------------------------
    // Unchanged behaviour that the new eviction must not disturb
    // -----------------------------------------------------------------------

    @Test
    void rootShortCircuitsWithoutTouchingTheDatabase() {
        var root = new RequestContext.Principal(1L, "root", true, COMPANY, BRANCH, null);
        assertThat(resolver.hasPermission(root, "ANYTHING.AT.ALL", NOW)).isTrue();
        assertThat(resolver.isMember(root, NOW)).isTrue();
    }

    @Test
    void invalidate_clearsEveryUser() {
        databaseGrants(USER, CODE);
        databaseGrants(OTHER, CODE);
        assertThat(resolver.resolve(USER, COMPANY, BRANCH, NOW)).contains(CODE);
        assertThat(resolver.resolve(OTHER, COMPANY, BRANCH, NOW)).contains(CODE);

        when(userRoles.resolvePermissionCodes(USER, COMPANY, BRANCH)).thenReturn(List.of());
        when(userRoles.resolvePermissionCodes(OTHER, COMPANY, BRANCH)).thenReturn(List.of());
        resolver.invalidate();

        assertThat(resolver.resolve(USER, COMPANY, BRANCH, NOW)).isEmpty();
        assertThat(resolver.resolve(OTHER, COMPANY, BRANCH, NOW)).isEmpty();
    }
}
