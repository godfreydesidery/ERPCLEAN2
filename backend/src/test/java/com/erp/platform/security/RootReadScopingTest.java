package com.erp.platform.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.parties.service.PartyBranchGuard;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

/**
 * The Phase 3 remnants of P3-8 (ADR-0062): places where a read dropped its predicate for root, or
 * distinguished two outcomes it should not have.
 *
 * <p>These all fail <b>open</b> and <b>silently</b> if regressed — nothing throws, a caller simply
 * receives rows or facts that are not theirs — which is exactly the shape a test has to pin down.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class RootReadScopingTest {

    private static final long HOME_ORG = 1L;
    private static final long OTHER_ORG = 2L;

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private static RequestContext.Principal caller(Long organisationId, boolean root) {
        return new RequestContext.Principal(7L, "admin", root, 10L, 100L, "127.0.0.1", organisationId);
    }

    @Nested
    @DisplayName("PartyBranchGuard — the existence oracle found in the P3-12 triage")
    class PartyBranch {

        @Mock private BranchRepository branches;
        @Mock private CompanyTenantIndex companyTenants;

        private static final long BRANCH_ID = 55L;
        private static final long BRANCH_COMPANY = 20L;
        private static final long PARTY_COMPANY = 21L;

        private PartyBranchGuard guard() {
            return new PartyBranchGuard(branches, new TenancyScopeEnforcer(), companyTenants);
        }

        private void givenBranchInCompany() {
            Company c = new Company(new Organisation("Org"), "C", "Co");
            setId(c, BRANCH_COMPANY);
            Branch b = new Branch(c, "BR", "Branch");
            setId(b, BRANCH_ID);
            when(branches.findById(BRANCH_ID)).thenReturn(Optional.of(b));
        }

        @Test
        @DisplayName("another tenant's branch is indistinguishable from one that does not exist")
        void crossTenantCollapsesToNotFound() {
            givenBranchInCompany();
            when(companyTenants.organisationOf(BRANCH_COMPANY)).thenReturn(OTHER_ORG);
            RequestContext.set(caller(HOME_ORG, false));

            // branchId is a sequential NUMBER — the one identifier here an outsider could guess,
            // since everything caller-facing is addressed by ULID uid. Telling them "that exists but
            // is not yours" turns a guess into a confirmed fact about another customer's estate.
            assertThatThrownBy(() -> guard().assertSameCompany(PARTY_COMPANY, BRANCH_ID))
                    .isInstanceOf(NotFoundException.class)
                    .hasMessageNotContainingAny("company", "organisation", "tenant");
        }

        @Test
        @DisplayName("a sibling company inside my own organisation still gets the real reason")
        void sameTenantKeepsTheInformativeMessage() {
            givenBranchInCompany();
            when(companyTenants.organisationOf(BRANCH_COMPANY)).thenReturn(HOME_ORG);
            RequestContext.set(caller(HOME_ORG, false));

            // This is the case the blanket fix would have broken. The caller owns BOTH companies, so
            // there is no boundary to leak across and they are entitled to know which rule they hit —
            // collapsing it too would have traded a real error message for no security at all.
            assertThatThrownBy(() -> guard().assertSameCompany(PARTY_COMPANY, BRANCH_ID))
                    .isInstanceOf(ForbiddenException.class)
                    .hasMessageContaining("does not belong to the same company");
        }

        @Test
        @DisplayName("the matching company is still accepted")
        void matchingCompanyPasses() {
            givenBranchInCompany();
            when(companyTenants.organisationOf(BRANCH_COMPANY)).thenReturn(HOME_ORG);
            RequestContext.set(caller(HOME_ORG, false));

            guard().assertSameCompany(BRANCH_COMPANY, BRANCH_ID);   // no throw
        }

        @Test
        @DisplayName("a branch that truly does not exist is not looked up for a tenant")
        void missingBranchNeedsNoTenantLookup() {
            when(branches.findById(BRANCH_ID)).thenReturn(Optional.empty());
            RequestContext.set(caller(HOME_ORG, false));

            assertThatThrownBy(() -> guard().assertSameCompany(PARTY_COMPANY, BRANCH_ID))
                    .isInstanceOf(NotFoundException.class);
            verify(companyTenants, never()).organisationOf(any());
        }
    }

    @Nested
    @DisplayName("the NULL-tolerance these predicates depend on")
    class NullTolerance {

        private final TenancyScopeEnforcer enforcer = new TenancyScopeEnforcer();

        @Test
        @DisplayName("an unattributed row is never foreign, so scoped reads cannot hide it")
        void unattributedRowsStayVisible() {
            // audit_logs.organisation_id was created by V99 and backfilled by V101, but nothing WROTE
            // it until AuditLog gained the field — so every row in between is null. The same is true
            // of any user account created through a path that missed the stamp. A strict predicate
            // would hide exactly the records an administrator needs in order to notice and fix the
            // gap, so every predicate added for P3-8 is NULL-tolerant.
            assertThat(enforcer.isForeignTenant(caller(HOME_ORG, true), null)).isFalse();
        }

        @Test
        @DisplayName("a root whose own organisation is unknown is not blanked out")
        void unattributedRootIsNotBlanked() {
            // Falling back to the old unscoped behaviour is deliberate: a data gap must not turn an
            // admin console into an empty screen with no explanation.
            assertThat(enforcer.isForeignTenant(caller(null, true), OTHER_ORG)).isFalse();
        }

        @Test
        @DisplayName("but a known mismatch is still refused, for root as much as anyone")
        void knownMismatchStillRefused() {
            assertThat(enforcer.isForeignTenant(caller(HOME_ORG, true), OTHER_ORG)).isTrue();
        }
    }

    private static void setId(Object entity, Long id) {
        for (Class<?> c = entity.getClass(); c != null; c = c.getSuperclass()) {
            try {
                var f = c.getDeclaredField("id");
                f.setAccessible(true);
                f.set(entity, id);
                return;
            } catch (NoSuchFieldException ignored) {
                // keep walking up
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("cannot set id on " + entity.getClass(), e);
            }
        }
        throw new IllegalStateException("no id field on " + entity.getClass());
    }
}
