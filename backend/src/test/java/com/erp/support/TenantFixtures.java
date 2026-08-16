package com.erp.support;

import com.erp.modules.iam.domain.entity.AppUser;

/**
 * Puts a test-built {@link AppUser} in an organisation (ADR-0062 P1-7).
 *
 * <h2>Why a wrapper and not a builder</h2>
 *
 * 193 call sites across 121 test files construct an {@code AppUser} directly, almost all of them
 * inside a repository save:
 *
 * <pre>{@code users.save(new AppUser("clerk", hash, "Clerk"))}</pre>
 *
 * When {@code app_users.organisation_id} becomes {@code NOT NULL}, every one of those breaks at
 * once. The obvious fixes are both expensive: a builder or a factory means rewriting the
 * construction at each site, and hoisting to a local means turning a one-line save into three
 * (declare, set, save) — in tests where the save is often already an argument to something else.
 *
 * <p>This returns the same instance so it composes as an <i>expression</i>:
 *
 * <pre>{@code users.save(inOrganisation(new AppUser("clerk", hash, "Clerk"), org.getId()))}</pre>
 *
 * which is a wrap in place — no restructuring, no new statements, and the diff stays legible on a
 * file with a dozen of them. That is what makes converting 193 sites a mechanical afternoon instead
 * of a refactor.
 *
 * <h2>Why tests should not just default this in the entity</h2>
 *
 * The tempting shortcut is to have {@code AppUser} fall back to some "default organisation" when
 * none is set. That would fix the fixtures and destroy the guarantee: a production path that
 * forgets to stamp the tenant would then silently get a valid-looking one instead of failing. The
 * column is the only authoritative source of a caller's tenant, so the constructor must keep
 * refusing to guess and the tests must say which tenant they mean.
 */
public final class TenantFixtures {

    private TenantFixtures() {
    }

    /**
     * Stamps {@code organisationId} on {@code user} and returns the same instance, so it can be
     * used inline where the constructor call currently sits.
     *
     * @param user           the user under construction; returned unchanged apart from the stamp
     * @param organisationId the owning organisation — normally {@code org.getId()} from the test's
     *                       own {@code @BeforeEach}, never a constant, so a test that means two
     *                       tenants can say so
     * @return {@code user}, for inline use
     */
    public static AppUser inOrganisation(AppUser user, Long organisationId) {
        user.setOrganisationId(organisationId);
        return user;
    }
}
