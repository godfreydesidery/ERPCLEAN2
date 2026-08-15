package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/**
 * Locks the exemption rule in {@link TenancyScopeEnforcer} (ADR-0062, MULTITENANCY-PLAN.md P2.5-1).
 *
 * <p>This class exists because the plan originally specified the rule <b>wrongly</b>, and the wrong
 * version is the one that looks natural. It said: treat a principal whose <i>organisation</i> is
 * null as SYSTEM and exempt it. That reads as harmless defensive coding and is a
 * privilege-escalation hole — a real user can legitimately have a null organisation, so exempting
 * there hands those accounts unscoped sessions through a data gap rather than an authorisation
 * decision.
 *
 * <p>If a future change makes {@link #aRealUserWithNoOrganisationIsDenied_notTreatedAsSystem} fail,
 * that is the hole reopening. Do not "fix" the test.
 */
class TenancyScopeEnforcerTest {

    private static final Long ORG_A = 1L;
    private static final Long ORG_B = 2L;

    private final TenancyScopeEnforcer enforcer = new TenancyScopeEnforcer();

    private static RequestContext.Principal user(Long organisationId) {
        return new RequestContext.Principal(7L, "cashier", false, 10L, 100L, "127.0.0.1", organisationId);
    }

    private static RequestContext.Principal root(Long organisationId) {
        return new RequestContext.Principal(1L, "rootadmin", true, 10L, 100L, "127.0.0.1", organisationId);
    }

    @Nested
    @DisplayName("the SYSTEM exemption keys on a null userId")
    class SystemExemption {

        @Test
        void aSystemPrincipalIsExempt_evenAgainstAForeignOrganisation() {
            // What the eighteen outbox handlers install when they replay a committed event. It is
            // not acting for a tenant; the event's company is already fixed. Denying it would fail
            // an asynchronous GL or stock posting that nobody is watching — the SalesPostingHandler
            // catches Exception and marks the event processed, so the loss would be silent.
            RequestContext.Principal system = RequestContext.Principal.system(10L, 100L);

            assertThat(system.system()).isTrue();
            assertThat(enforcer.isSameTenant(system, ORG_B)).isTrue();
        }

        @Test
        void aNullPrincipalIsExempt_forScheduledAndAsyncThreads() {
            // @Scheduled sweeps and @Async dispatch run with no request context at all.
            assertThat(enforcer.isSameTenant(null, ORG_B)).isTrue();
        }

        @Test
        void aRealUserWithNoOrganisationIsDenied_notTreatedAsSystem() {
            // THE POINT OF THIS CLASS. Every account created between V101 and the constraining
            // migration has a null organisation. Under the plan's original wording each of them
            // would have been exempt from every tenancy check — the newer the account, the more
            // privileged. A null userId cannot be produced by missing data; a null organisation can.
            RequestContext.Principal realUserNoOrg = user(null);

            assertThat(realUserNoOrg.system()).isFalse();
            assertThat(enforcer.isSameTenant(realUserNoOrg, ORG_A)).isFalse();
            assertThatThrownBy(() -> enforcer.assertSameTenant(realUserNoOrg, ORG_A, "thing"))
                    .isInstanceOf(AccessDeniedException.class);
        }
    }

    @Nested
    @DisplayName("isForeignTenant — the asymmetric rule ScopeGuard.canActIn needs")
    class ForeignTenant {

        @Test
        void aKnownMismatchIsForeign() {
            // The security property P3-11 exists for. Root is included deliberately: is_root is
            // deployment-global, and canActIn's `root ||` short-circuits the company comparison, so
            // without this a single is_root row reads and writes every tenant's ledger through a
            // query parameter — no header, no exploit.
            assertThat(enforcer.isForeignTenant(user(ORG_A), ORG_B)).isTrue();
            assertThat(enforcer.isForeignTenant(root(ORG_A), ORG_B)).isTrue();
        }

        @Test
        void theSameOrganisationIsNotForeign() {
            assertThat(enforcer.isForeignTenant(user(ORG_A), ORG_A)).isFalse();
        }

        @Test
        void anUnattributedCallerIsNotForeign_thisIsNotIsSameTenantNegated() {
            // THE POINT OF THIS NESTED CLASS, and the one place isForeignTenant and !isSameTenant
            // disagree. isSameTenant answers "may this caller touch that row?", so an unknown
            // organisation must answer no. canActIn asks a different question, under every one of
            // 698 call sites: deny an unattributed account there and it does not lose a screen, it
            // loses the product — refused by a NOT NULL column that has not landed rather than by
            // any authorisation decision. A caller cannot null their own organisation, so this
            // branch is a data gap, not an input.
            assertThat(enforcer.isForeignTenant(user(null), ORG_A)).isFalse();
            assertThat(enforcer.isSameTenant(user(null), ORG_A)).isFalse();   // and they differ
        }

        @Test
        void anUnknownTargetIsNotForeign() {
            // companies.organisation_id is NOT NULL, so a null target means the company id does not
            // exist. canActIn's own rules refuse that on their own terms; it is not a tenant call.
            assertThat(enforcer.isForeignTenant(user(ORG_A), null)).isFalse();
        }

        @Test
        void systemAndNullPrincipalsAreNeverForeign() {
            // The eighteen outbox handlers replaying a committed event, and @Scheduled/@Async
            // threads with no request context at all.
            assertThat(enforcer.isForeignTenant(RequestContext.Principal.system(10L, 100L), ORG_B))
                    .isFalse();
            assertThat(enforcer.isForeignTenant(null, ORG_B)).isFalse();
        }
    }

    @Nested
    @DisplayName("ordinary comparisons")
    class Comparisons {

        @Test
        void sameOrganisationIsAllowed() {
            assertThat(enforcer.isSameTenant(user(ORG_A), ORG_A)).isTrue();
        }

        @Test
        void aDifferentOrganisationIsRefused() {
            assertThat(enforcer.isSameTenant(user(ORG_A), ORG_B)).isFalse();
            assertThatThrownBy(() -> enforcer.assertSameTenant(user(ORG_A), ORG_B, "thing"))
                    .isInstanceOf(AccessDeniedException.class);
        }

        @Test
        void bothNullIsAllowed_transitionalUntilTheColumnIsNotNull() {
            // A legacy row and a pre-P2-1 row can both be null. Refusing there would break working
            // installations for no security gain: at one organisation there is nothing to cross.
            // This case disappears on its own when the column becomes NOT NULL.
            assertThat(enforcer.isSameTenant(user(null), null)).isTrue();
        }

        @Test
        void theRefusalSaysNothingAboutOrganisations() {
            // Telling a caller that a thing exists but belongs to someone else is an existence
            // oracle across a tenant boundary. The refusal must read like "not found here".
            assertThatThrownBy(() -> enforcer.assertSameTenant(user(ORG_A), ORG_B, "invoice"))
                    .isInstanceOf(AccessDeniedException.class)
                    .hasMessageNotContainingAny("organisation", "tenant", "invoice", "2");
        }
    }
}
