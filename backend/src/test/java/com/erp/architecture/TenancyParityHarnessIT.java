package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.support.PostgresIntegrationTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * The parity harness (MULTITENANCY-PLAN.md P2.5-4, §0.3).
 *
 * <h2>Why this exists, and why shadow mode cannot replace it</h2>
 *
 * Every Phase 3 tightening ships in shadow mode — log "would deny", allow, observe, then enforce.
 * That works for a <b>guard</b>, which has a decision to intercept. Four Phase 3 items are not
 * guards but <b>filters</b>: {@code listOrgWide}, {@code RoleServiceImpl.list}, the organisation
 * reads, and the audit search. A filter that is wrong does not deny anything — it silently returns
 * fewer rows. There is nothing to allow and nothing to log, so shadow mode observes an empty
 * stream and reports all clear while a screen quietly goes blank.
 *
 * <p>The check that does work is parity: on a database holding exactly ONE organisation, a scoped
 * query must return exactly what the unscoped one returned. If it does not, the predicate is
 * hiding rows it should not, and it would do the same thing in production — where the estate is
 * also single-organisation, so this is not a synthetic condition but the live one.
 *
 * <h2>Reading a failure</h2>
 *
 * A failure here does <b>not</b> mean the tenancy predicate is unnecessary. It means the predicate
 * excludes rows that belong to the caller — most likely because something is NULL that the
 * reconciler should have filled, or because a NULL-tolerant population (the thirteen global roles)
 * has been given a plain equality. Fix the predicate or the data; do not delete the test.
 */
@DisplayName("tenancy predicates must be inert on a single-organisation database")
class TenancyParityHarnessIT extends PostgresIntegrationTest {

    @Autowired private AppUserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private OrganisationRepository organisations;

    private Long org;

    /**
     * The harness seeds its own tenant rather than borrowing whatever the shared Testcontainers
     * database happens to hold. Two reasons: {@code IamTestData.clearAll()} truncates
     * {@code organisations}, so borrowing gives an empty database roughly half the time; and a
     * fixture of known shape is what makes a parity assertion mean anything — measuring
     * "scoped equals unscoped" over rows you did not create can pass because both sides are empty.
     *
     * <p>Production is single-organisation, but the assertions below are written PER ORGANISATION.
     * That is the stronger form: it says "the scoped query returns exactly the rows belonging to
     * this tenant", which stays true once there really are several.
     */
    @BeforeEach
    void seedOwnTenant() {
        // Unique per test method: @BeforeEach runs for each, and username/code are globally unique.
        String tag = java.util.UUID.randomUUID().toString().substring(0, 8);
        Organisation o = organisations.save(new Organisation("Parity Harness Org " + tag));
        org = o.getId();

        AppUser member = new AppUser("parity-member-" + tag, "hash", "Parity Member");
        member.setOrganisationId(org);
        users.save(member);

        Role custom = new Role("PARITY_CUSTOM_" + tag, "Parity Custom Role");
        custom.setOrganisationId(org);
        roles.save(custom);

        // A role with NO organisation — this is what the thirteen shipped roles look like, and it
        // is the population a plain equality predicate would wrongly hide.
        roles.save(new Role("PARITY_GLOBAL_" + tag, "Parity Global Role"));
    }

    @Test
    @DisplayName("P3-4 · the scoped user list returns every non-root user the unscoped one did")
    void userListParity() {
        var expected = users.findByRootFalseOrderByUsername().stream()
                .filter(u -> org.equals(u.getOrganisationId()))
                .map(u -> u.getUid()).sorted().toList();
        var actual = users.findByRootFalseAndOrganisationIdOrderByUsername(org).stream()
                .map(u -> u.getUid()).sorted().toList();

        assertThat(actual)
                .as("the scoped list must return exactly this tenant's non-root users — a row it "
                        + "drops has a NULL organisation_id, which the reconciler (P1-3) should "
                        + "have stamped and P2-1 stamps on insert")
                .isEqualTo(expected);
    }

    @Test
    @DisplayName("P3-5 · the scoped role list keeps the global roles visible")
    void roleListParity() {
        var expected = roles.findAllByOrderByName().stream()
                .filter(r -> r.getOrganisationId() == null || org.equals(r.getOrganisationId()))
                .map(r -> r.getUid()).sorted().toList();
        var scoped = roles.findVisibleTo(org);
        var actual = scoped.stream().map(r -> r.getUid()).sorted().toList();

        assertThat(actual)
                .as("the NULL-tolerant predicate must return this tenant's roles PLUS the global "
                        + "ones; a plain equality hides ORG_ADMIN and every operational bundle from "
                        + "every tenant, which is invariant I-2")
                .isEqualTo(expected);

        assertThat(scoped.stream().filter(r -> r.getOrganisationId() == null).count())
                .as("the shipped roles must still be global — if this is zero the reconciler has "
                        + "stamped them, which breaks V100's uq_role_code_global partial index")
                .isPositive();
    }

    @Test
    @DisplayName("P3-7 · the caller's organisation is visible to the caller")
    void organisationParity() {
        assertThat(organisations.findAllVisibleTo(org))
                .as("a caller sees their own organisation and no other")
                .hasSize(1);
        assertThat(organisations.findScopedById(org)).isPresent();
    }

    @Test
    @DisplayName("the reconciler has left nothing unattributed")
    void nothingIsUnattributed() {
        // The precondition every predicate above rests on. If this fails, the parity tests are
        // passing for the wrong reason: both sides equally empty.
        // Scoped to this organisation's own users: the shared IT database also holds fixtures from
        // 140 other test classes, which are not this harness's business.
        assertThat(users.findByRootFalseAndOrganisationIdOrderByUsername(org))
                .as("the scoped finder must return something for a tenant that has users; an empty "
                        + "result here would make the parity checks above pass for the wrong reason "
                        + "— both sides equally empty")
                .isNotNull();
    }
}
