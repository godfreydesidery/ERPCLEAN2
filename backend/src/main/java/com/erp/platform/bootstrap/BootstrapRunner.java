package com.erp.platform.bootstrap;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.platform.security.password.PasswordPolicy;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * On startup, if bootstrap is enabled and the DB has no organisation, creates the initial
 * organisation + company + default branch + root admin from {@link BootstrapProperties}
 * (FR-IAM-22). Idempotent: skips if any organisation already exists.
 *
 * <p>Fail-closed on a weak/missing admin password: a bootstrap-enabled app with an invalid password
 * refuses to start, so we never create a root admin with a guessable password.
 *
 * <p>Per-company defaults (UoM, tax rates, GL, etc.) are delegated to
 * {@link CompanyProvisioningService}. The branch-scoped stock-location seeder stays here
 * because it also needs the branch id.
 */
@Component
@EnableConfigurationProperties(BootstrapProperties.class)
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    /** Minimum strength for the root admin specifically (stricter than the general user policy). */
    private static final int ROOT_MIN_LENGTH = 12;
    private static final Set<String> PLACEHOLDERS =
            Set.of("changeme", "password", "admin", "rootadmin", "secret", "changeme123");

    private final BootstrapProperties        props;
    private final OrganisationRepository     organisations;   // the empty-database guard
    private final AppUserRepository          users;
    private final PasswordPolicy             passwordPolicy;
    private final TenantProvisioningService   tenantProvisioning;
    // Stock location seeder — seeds WAREHOUSE + in-transit per branch (ADR-0028 D-4/D-5).
    // Branch-scoped: stays here (needs branchId), not moved into CompanyProvisioningService.
    // Petty cash default fund seeder (ADR-0050 D-7 PR-B): CompanyProvisioningService.provisionDefaults
    // (below) runs BEFORE the bootstrap branch exists, so it is a no-op there; this branch-scoped
    // call completes the seed once the branch is created, mirroring stockLocationSeeder above.

    public BootstrapRunner(BootstrapProperties       props,
                           OrganisationRepository    organisations,
                           AppUserRepository         users,
                           PasswordPolicy            passwordPolicy,
                           TenantProvisioningService tenantProvisioning) {
        this.props              = props;
        this.organisations      = organisations;
        this.users              = users;
        this.passwordPolicy     = passwordPolicy;
        this.tenantProvisioning = tenantProvisioning;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (!props.enabled()) {
            return;
        }
        if (organisations.count() > 0) {
            log.info("Bootstrap skipped: organisation already exists.");
            return;
        }

        validateAdminPassword(props.adminPassword());

        // P5-1 (ADR-0062): delegated, not inlined. A create-tenant endpoint has to produce exactly
        // this - organisation, company, branch, admin, and every company-scoped default beneath them
        // - and two copies of forty lines of seeding drift. Drift here means a tenant that boots into
        // half-configured screens, so bootstrap now takes the same path the second tenant will.
        BootstrapProperties.CurrencyConfig ccy = props.effectiveCurrency();
        var provisioned = tenantProvisioning.provision(new TenantProvisioningService.NewTenantRequest(
                props.organisationName(), props.timeZone(),
                props.companyCode(), props.companyName(),
                // Legal name, TIN, VRN: bootstrap asks for none of them and creates none of them.
                // A fresh install must produce exactly what it produced before these components
                // existed — this is the ONE path no test environment ever executes (it runs only on
                // an empty database), so the customer is the first to run it, and that property has
                // already cost two live outages. Deliberately not wired to new ERP_BOOTSTRAP_* keys.
                null, null, null,
                props.branchCode(), props.branchName(),
                props.adminUsername(), props.adminPassword(), props.adminDisplayName(),
                ccy.effectiveBase(), ccy.effectiveDefault(), ccy.effectiveEnabled(),
                // Price-list code + name + VAT stance, walk-in customer, till: likewise none. The
                // first tenant's administrator creates them from the shipped screens.
                null, null, null, null, null,
                false,   // bootstrap admin stays PLAIN — INSTALL.md promises 'always rootadmin'
                // ...and IS the platform operator: root. This is the only path that may set it,
                // and there is no second chance — is_root is settable nowhere else in the
                // application, so a fresh install whose rootadmin is not root can never get one.
                true));

        log.info("Bootstrap complete: organisation '{}', company '{}', branch '{}', root admin '{}'.",
                provisioned.organisationName(), provisioned.companyCode(),
                provisioned.branchCode(), provisioned.adminUsername());
    }

    /** Fail-closed: refuse to start with a missing, too-short, or placeholder root password. */
    private void validateAdminPassword(String password) {
        if (password == null || password.isBlank()) {
            throw new IllegalStateException(
                    "ERP_BOOTSTRAP_ADMIN_PASSWORD must be set when bootstrap is enabled.");
        }
        if (password.length() < ROOT_MIN_LENGTH) {
            throw new IllegalStateException(
                    "Bootstrap admin password must be at least " + ROOT_MIN_LENGTH + " characters.");
        }
        if (PLACEHOLDERS.contains(password.toLowerCase())) {
            throw new IllegalStateException(
                    "Bootstrap admin password is a known placeholder; choose a real password.");
        }
        // Also apply the general policy (letters + digit).
        passwordPolicy.validate(password);
    }
}
