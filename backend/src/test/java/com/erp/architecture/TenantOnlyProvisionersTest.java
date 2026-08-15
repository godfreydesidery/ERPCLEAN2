package com.erp.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The price list, walk-in customer and POS till may be created ONLY on the tenant-creation path.
 *
 * <h2>Why this is a build gate and not a convention</h2>
 *
 * Every other per-company default is a {@code *Seeder} called from
 * {@code CompanyProvisioningServiceImpl.provisionDefaults} — which is also the HEAL path, reachable
 * from {@code POST /api/v1/companies/uid/{uid}/provision-defaults} and run against companies that
 * have traded for years. There is one live paying customer with years of real data, and that
 * endpoint runs against their companies.
 *
 * <p>These three are different in kind: their values (a price-list name, the counter customer's
 * name, what is written on the till) are things a human knows, so there is nothing for a heal to
 * seed. Keeping them out of the chain means re-provisioning a live company has no row to write, and
 * the whole "did the idempotency guard hold on a company with years of data?" question stops
 * existing rather than being defended.
 *
 * <p>Reading the code proves that today. It does not stop the next engineer from adding a
 * {@code posTillProvisioner.createIfNamed(...)} to the seeder chain in six months, which is exactly
 * the regression the design exists to prevent — so the "don't" becomes a rule, as
 * {@code OrganisationWriteMappingsAreAllowlistedTest} and {@code TenantScopingRulesTest} already do
 * for their own invariants.
 *
 * <p>If this fails because you added a legitimate second caller, that caller must be on the
 * tenant-creation path. If it is {@code CompanyProvisioningServiceImpl}, it is not — move the call,
 * do not amend the rule.
 */
class TenantOnlyProvisionersTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.erp");

    private static final String[] TENANT_ONLY_PROVISIONERS = {
            "com.erp.modules.products.service.DefaultPriceListProvisioner",
            "com.erp.modules.parties.service.WalkInCustomerProvisioner",
            "com.erp.modules.sales.service.PosTillProvisioner",
    };

    @Test
    @DisplayName("only TenantProvisioningService may use the three request-driven provisioners")
    void onlyTenantProvisioningMayUseThem() {
        ArchRule rule = noClasses()
                .that().haveNameNotMatching(
                        "com\\.erp\\.platform\\.bootstrap\\.TenantProvisioningService")
                .and().haveNameNotMatching(
                        "com\\.erp\\.modules\\.(products|parties|sales)\\.service\\."
                                + "(DefaultPriceListProvisioner|WalkInCustomerProvisioner|"
                                + "PosTillProvisioner)")
                .should().dependOnClassesThat().haveFullyQualifiedName(TENANT_ONLY_PROVISIONERS[0])
                .orShould().dependOnClassesThat().haveFullyQualifiedName(TENANT_ONLY_PROVISIONERS[1])
                .orShould().dependOnClassesThat().haveFullyQualifiedName(TENANT_ONLY_PROVISIONERS[2])
                .as("the price list, walk-in customer and till are created ONLY when a tenant is "
                        + "created. Adding one to CompanyProvisioningServiceImpl's seeder chain "
                        + "would fire it on the heal endpoint, against a live customer's companies.")
                .allowEmptyShould(true);

        rule.check(CLASSES);
    }
}
