package com.erp.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.bootstrap.TenantProvisioningService.NewTenantRequest;
import com.erp.platform.security.password.PasswordPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.DefaultApplicationArguments;

/**
 * What {@link BootstrapRunner} asks for — the first-install call site, pinned.
 *
 * <h2>Why this class exists at all</h2>
 *
 * Bootstrap runs ONLY on an empty database, so no test environment, no QA box and no upgrade ever
 * executes it: the customer's fresh install is its first real run. That property has already cost two
 * live outages. Every other path is exercised long before it ships; this one is exercised by pinning
 * what it passes.
 *
 * <p>The owner's instruction for the tenant-two work was that a fresh install must produce exactly
 * what it produced before the tax identity, price list, counter customer and till existed. That
 * property has two halves, and neither test can hold both:
 * <ul>
 *   <li><b>here</b> — bootstrap asks for none of them, i.e. every optional component of
 *       {@link NewTenantRequest} arrives null;</li>
 *   <li><b>{@code TenantProvisioningServiceTest}</b> — a request shaped like that writes no row,
 *       asserted against the repositories rather than against what was passed on.</li>
 * </ul>
 *
 * <p>If you are here because this test went red after wiring a new {@code ERP_BOOTSTRAP_*} key into
 * the {@code provision(...)} call: that is the test doing its job, not a stale assertion. A fresh
 * install would start creating rows it has never created, and the customer would be the first to find
 * out. Take it to the owner before changing this file.
 */
@ExtendWith(MockitoExtension.class)
class BootstrapRunnerTest {

    @Mock OrganisationRepository organisations;
    @Mock AppUserRepository users;
    @Mock PasswordPolicy passwordPolicy;
    @Mock TenantProvisioningService tenantProvisioning;

    private BootstrapRunner runner;

    @BeforeEach
    void setUp() {
        BootstrapProperties props = new BootstrapProperties(
                true, "Tembo Group", "TG", "Tembo Group Ltd", "HQ", "Head Office",
                "rootadmin", "Root Admin", "RootPass12345", "Africa/Dar_es_Salaam", null);
        runner = new BootstrapRunner(props, organisations, users, passwordPolicy, tenantProvisioning);
    }

    @Test
    @DisplayName("a fresh install asks for no tax identity, no price list, no customer and no till")
    void bootstrapAsksForNoneOfTheOptionalComponents() {
        when(organisations.count()).thenReturn(0L);
        when(tenantProvisioning.provision(any())).thenReturn(provisioned());

        runner.run(new DefaultApplicationArguments());

        NewTenantRequest asked = captured();
        assertThat(asked.companyLegalName()).isNull();
        assertThat(asked.companyTaxId()).isNull();
        assertThat(asked.companyVrn()).isNull();
        assertThat(asked.priceListCode()).isNull();
        assertThat(asked.priceListName()).isNull();
        assertThat(asked.priceListIncludesVat()).isNull();
        assertThat(asked.walkInCustomerName()).isNull();
        assertThat(asked.posTillName()).isNull();
    }

    /**
     * The values bootstrap DOES pass, so a refactor that reorders the argument list cannot quietly
     * make every assertion above pass for the wrong reason — a run of nulls is order-insensitive, and
     * these are what tell a transposition from a deletion.
     */
    @Test
    @DisplayName("the configured organisation, company, branch, admin and currency still arrive")
    void configuredValuesStillArrive() {
        when(organisations.count()).thenReturn(0L);
        when(tenantProvisioning.provision(any())).thenReturn(provisioned());

        runner.run(new DefaultApplicationArguments());

        NewTenantRequest asked = captured();
        assertThat(asked.organisationName()).isEqualTo("Tembo Group");
        assertThat(asked.companyCode()).isEqualTo("TG");
        assertThat(asked.companyName()).isEqualTo("Tembo Group Ltd");
        assertThat(asked.branchCode()).isEqualTo("HQ");
        assertThat(asked.branchName()).isEqualTo("Head Office");
        assertThat(asked.adminUsername()).isEqualTo("rootadmin");
        assertThat(asked.adminDisplayName()).isEqualTo("Root Admin");
        assertThat(asked.timeZone()).isEqualTo("Africa/Dar_es_Salaam");
        assertThat(asked.baseCurrency()).isEqualTo("TZS");
        // dist/bundle/.env.example and INSTALL.md both promise the first administrator is "always
        // rootadmin"; composing the alias onto it would break that on every fresh install (D-7).
        assertThat(asked.composeAdminUsername()).isFalse();
    }

    @Test
    @DisplayName("a database that already has an organisation is left completely alone")
    void anAlreadyProvisionedDatabaseIsNotTouched() {
        when(organisations.count()).thenReturn(1L);

        runner.run(new DefaultApplicationArguments());

        verifyNoInteractions(tenantProvisioning, passwordPolicy, users);
    }

    // -------------------------------------------------------------------------

    private NewTenantRequest captured() {
        ArgumentCaptor<NewTenantRequest> captor = ArgumentCaptor.forClass(NewTenantRequest.class);
        verify(tenantProvisioning).provision(captor.capture());
        return captor.getValue();
    }

    private static TenantProvisioningService.ProvisionedTenant provisioned() {
        return new TenantProvisioningService.ProvisionedTenant(1L, 2L, 3L, 4L,
                "Tembo Group", "TG", "HQ", "rootadmin");
    }
}
