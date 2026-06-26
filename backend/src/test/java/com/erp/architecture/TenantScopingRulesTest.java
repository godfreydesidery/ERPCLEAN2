package com.erp.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.Test;
import org.springframework.data.repository.Repository;

/**
 * Tenant-isolation structural guard (added 2026-06-26 after the cross-company "confused-deputy"
 * audit; see the tenant-isolation memory + commit 33cf4f0).
 *
 * <p><b>What it prevents:</b> the confused-deputy vulnerability class where a service scope-checks a
 * caller-supplied {@code companyId} (the attacker passes their OWN company, so it passes) and then
 * loads a company-owned entity by a SEPARATE, unverified numeric id taken from request input — e.g.
 * {@code accounts.findById(accountId)}. Numeric ids are exposed on DTOs (id/uid duality), so they
 * are enumerable, and the bare lookup returns/mutates another company's row.
 *
 * <p><b>The rule:</b> a class under {@code ..service..} must not call {@code findById} /
 * {@code getReferenceById} on a Spring Data {@link Repository}. Resolve company-owned entities with a
 * <em>company-scoped</em> finder instead — {@code findByCompanyIdAndId(companyId, id)} /
 * {@code existsByCompanyIdAndId(...)} — which filters in the database and never loads the foreign row.
 *
 * <p><b>Why frozen:</b> the codebase already has ~200 legitimate by-id lookups (loading the company
 * by its own scope id, lookups after an {@code assertCanActIn}, posting internals). Those were swept
 * empirically in the audit and are recorded as a baseline in
 * {@code src/test/resources/archunit_freeze}. Only NEW {@code findById}/{@code getReferenceById}
 * calls from services fail the build. To intentionally add one (with review), regenerate the store
 * locally with {@code -Dfreeze.store.default.allowStoreUpdate=true} and commit the diff.
 *
 * <p>Note: {@code findByUid} is intentionally out of scope — uid-addressed reads are guarded by
 * {@code @perm.scoped} on controllers and the load-then-{@code assertCanActIn} convention; that
 * surface is covered by the live e2e isolation harness instead.
 */
class TenantScopingRulesTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.erp");

    private static final DescribedPredicate<JavaCall<?>> BARE_BY_ID_LOOKUP_ON_REPOSITORY =
            DescribedPredicate.describe(
                    "call findById/getReferenceById on a Spring Data repository",
                    call -> {
                        String name = call.getTarget().getName();
                        return (name.equals("findById") || name.equals("getReferenceById"))
                                && call.getTarget().getOwner().isAssignableTo(Repository.class);
                    });

    @Test
    void servicesMustResolveCompanyOwnedEntitiesByCompanyScopedFinder() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..service..")
                .should().callMethodWhere(BARE_BY_ID_LOOKUP_ON_REPOSITORY)
                .as("services must resolve entities via a company-scoped finder "
                        + "(findByCompanyIdAndId/existsByCompanyIdAndId), not bare "
                        + "findById/getReferenceById — prevents confused-deputy cross-tenant access");

        FreezingArchRule.freeze(rule).check(CLASSES);
    }
}
