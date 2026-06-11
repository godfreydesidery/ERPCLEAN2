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
import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.crm.service.CrmStageSeeder;
import com.erp.modules.documents.service.DocumentBrandingSeeder;
import com.erp.modules.ar.service.ArGlSeeder;
import com.erp.modules.cashbank.service.CashBankSeeder;
import com.erp.modules.stock.service.InventoryGlSeeder;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.products.service.UnitOfMeasureSeeder;
import com.erp.modules.sales.service.TaxRateSeeder;
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
 */
@Component
@EnableConfigurationProperties(BootstrapProperties.class)
public class BootstrapRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapRunner.class);

    /** Minimum strength for the root admin specifically (stricter than the general user policy). */
    private static final int ROOT_MIN_LENGTH = 12;
    private static final Set<String> PLACEHOLDERS =
            Set.of("changeme", "password", "admin", "rootadmin", "secret", "changeme123");

    private final BootstrapProperties props;
    private final OrganisationRepository organisations;
    private final CompanyRepository companies;
    private final BranchRepository branches;
    private final AppUserRepository users;
    private final UserBranchRepository userBranches;
    private final PasswordEncoder passwordEncoder;
    private final PasswordPolicy passwordPolicy;
    private final UnitOfMeasureSeeder unitSeeder;
    private final TaxRateSeeder taxRateSeeder;
    // GL seeders (ADR-0013 D-15)
    private final ChartOfAccountService chartOfAccountService;
    private final FiscalCalendarService fiscalCalendarService;
    private final GlConfigService glConfigService;
    // AR/AP GL seeders (ADR-0014/0015)
    private final ArGlSeeder arGlSeeder;
    private final ApGlSeeder apGlSeeder;
    // Cash & Bank seeder (ADR-0016 D-10)
    private final CashBankSeeder cashBankSeeder;
    // Inventory Valuation & COGS seeder (ADR-0020 D-8)
    private final InventoryGlSeeder inventoryGlSeeder;
    // Document branding + template registry seeder (ADR-0023 D-10)
    private final DocumentBrandingSeeder documentBrandingSeeder;
    // CRM pipeline stage defaults seeder (ADR-0031 D-5)
    private final CrmStageSeeder crmStageSeeder;

    public BootstrapRunner(BootstrapProperties props,
                           OrganisationRepository organisations,
                           CompanyRepository companies,
                           BranchRepository branches,
                           AppUserRepository users,
                           UserBranchRepository userBranches,
                           PasswordEncoder passwordEncoder,
                           PasswordPolicy passwordPolicy,
                           UnitOfMeasureSeeder unitSeeder,
                           TaxRateSeeder taxRateSeeder,
                           ChartOfAccountService chartOfAccountService,
                           FiscalCalendarService fiscalCalendarService,
                           GlConfigService glConfigService,
                           ArGlSeeder arGlSeeder,
                           ApGlSeeder apGlSeeder,
                           CashBankSeeder cashBankSeeder,
                           InventoryGlSeeder inventoryGlSeeder,
                           DocumentBrandingSeeder documentBrandingSeeder,
                           CrmStageSeeder crmStageSeeder) {
        this.props = props;
        this.organisations = organisations;
        this.companies = companies;
        this.branches = branches;
        this.users = users;
        this.userBranches = userBranches;
        this.passwordEncoder = passwordEncoder;
        this.passwordPolicy = passwordPolicy;
        this.unitSeeder = unitSeeder;
        this.taxRateSeeder = taxRateSeeder;
        this.chartOfAccountService = chartOfAccountService;
        this.fiscalCalendarService = fiscalCalendarService;
        this.glConfigService = glConfigService;
        this.arGlSeeder        = arGlSeeder;
        this.apGlSeeder        = apGlSeeder;
        this.cashBankSeeder    = cashBankSeeder;
        this.inventoryGlSeeder      = inventoryGlSeeder;
        this.documentBrandingSeeder = documentBrandingSeeder;
        this.crmStageSeeder         = crmStageSeeder;
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

        // Seed default units of measure for the bootstrap company (brief §New company → seed defaults).
        unitSeeder.seedDefaults(company.getId());

        // Seed default VAT rates for the bootstrap company (ADR-0008 D-5b).
        taxRateSeeder.seedDefaults(company.getId());

        // Seed default Chart of Accounts, fiscal year + periods, GL config mappings (ADR-0013 D-15).
        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        // Seed AR/AP GL accounts + gl_configs (ADR-0014/0015 D-13).
        arGlSeeder.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        // Seed default Cash & Bank account (ADR-0016 D-10).
        cashBankSeeder.seedDefaults(company.getId());
        // Seed GRNI + Stock Adjustment GL accounts + gl_configs (ADR-0020 D-8).
        inventoryGlSeeder.seedDefaults(company.getId());
        // Seed document branding profile + template registry (ADR-0023 D-10).
        documentBrandingSeeder.seedDefaults(company.getId());
        // Seed default CRM pipeline stages (ADR-0031 D-5).
        crmStageSeeder.seedDefaults(company.getId());

        Branch branch = new Branch(company, props.branchCode(), props.branchName());
        branch.setTimeZone(props.timeZone());
        branch.setDefault(true);
        branches.save(branch);

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
