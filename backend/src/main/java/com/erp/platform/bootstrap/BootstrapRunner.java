package com.erp.platform.bootstrap;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.stock.service.StockLocationSeeder;
import com.erp.platform.security.password.PasswordPolicy;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.security.crypto.password.PasswordEncoder;
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
    private final OrganisationRepository     organisations;
    private final CompanyRepository          companies;
    private final BranchRepository           branches;
    private final AppUserRepository          users;
    private final UserBranchRepository       userBranches;
    private final PasswordEncoder            passwordEncoder;
    private final PasswordPolicy             passwordPolicy;
    // Stock location seeder — seeds WAREHOUSE + in-transit per branch (ADR-0028 D-4/D-5).
    // Branch-scoped: stays here (needs branchId), not moved into CompanyProvisioningService.
    private final StockLocationSeeder        stockLocationSeeder;
    private final CompanyProvisioningService companyProvisioningService;

    public BootstrapRunner(BootstrapProperties        props,
                           OrganisationRepository     organisations,
                           CompanyRepository          companies,
                           BranchRepository           branches,
                           AppUserRepository          users,
                           UserBranchRepository       userBranches,
                           PasswordEncoder            passwordEncoder,
                           PasswordPolicy             passwordPolicy,
                           StockLocationSeeder        stockLocationSeeder,
                           CompanyProvisioningService companyProvisioningService) {
        this.props                    = props;
        this.organisations            = organisations;
        this.companies                = companies;
        this.branches                 = branches;
        this.users                    = users;
        this.userBranches             = userBranches;
        this.passwordEncoder          = passwordEncoder;
        this.passwordPolicy           = passwordPolicy;
        this.stockLocationSeeder      = stockLocationSeeder;
        this.companyProvisioningService = companyProvisioningService;
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

        Organisation org = new Organisation(props.organisationName());
        org.setDefaultTimeZone(props.timeZone());
        organisations.save(org);

        Company company = new Company(org, props.companyCode(), props.companyName());
        company.setTimeZone(props.timeZone());
        companies.save(company);

        // Provision all company-scoped defaults (UoM, tax rates, GL, AR/AP, cash/bank,
        // inventory GL, documents, fixed assets, costing dimensions, CRM stages, HR GL +
        // statutory, notifications, manufacturing GL, currency enablement — ADR-0013..0039).
        BootstrapProperties.CurrencyConfig ccy = props.effectiveCurrency();
        companyProvisioningService.provisionDefaults(
                company.getId(),
                ccy.effectiveBase(),
                ccy.effectiveDefault(),
                ccy.effectiveEnabled());

        Branch branch = new Branch(company, props.branchCode(), props.branchName());
        branch.setTimeZone(props.timeZone());
        branch.setDefault(true);
        branches.save(branch);

        // Seed WAREHOUSE default + in-transit OTHER locations for the bootstrap branch (ADR-0028 D-4/D-5).
        stockLocationSeeder.seedDefaults(company.getId(), branch.getId(), branch.getCode());

        AppUser root = new AppUser(
                props.adminUsername().toLowerCase(),
                passwordEncoder.encode(props.adminPassword()),
                props.adminDisplayName());
        root.setRoot(true);
        users.save(root);  // save first so root.getId() is populated

        UserBranch defaultAssignment = new UserBranch(root.getId(), branch, root.getId());
        defaultAssignment.markDefault();
        userBranches.save(defaultAssignment);

        log.info("Bootstrap complete: organisation '{}', company '{}', branch '{}', root admin '{}'.",
                org.getName(), company.getCode(), branch.getCode(), root.getUsername());
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
