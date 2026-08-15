package com.erp.platform.bootstrap;

import com.erp.modules.hr.service.LeaveTypeSeeder;
import com.erp.modules.cashbank.service.PettyCashFundSeeder;
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
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates a complete, usable tenant: organisation, company, branch, administrator, and every
 * company-scoped default beneath them (ADR-0062 P5-1).
 *
 * <h2>Why this is extracted rather than left in {@code BootstrapRunner}</h2>
 *
 * The whole of tenant creation lived inside an {@link org.springframework.boot.ApplicationRunner}
 * that only fires on an empty database. That is exactly the wrong place for it once a second tenant
 * can exist: a create-tenant endpoint (P5-2) would either duplicate forty lines of seeding or drift
 * from it, and drift here means a tenant that boots into half-configured screens.
 *
 * <p>Extracting it also makes the omissions visible. Two were found in the process, and both would
 * have hit only the SECOND tenant — the first is created by bootstrap on an empty database where the
 * migrations happen to have run their own seeding:
 * <ul>
 *   <li><b>P5-5</b> — {@code leave_types} is seeded by {@code V52__hr_leave_loans.sql} with
 *       {@code CROSS JOIN companies}, so it covers companies that existed WHEN THE MIGRATION RAN. A
 *       company created afterwards opens HR → Leave completely empty, and nothing in Java ever
 *       filled it.</li>
 *   <li><b>P5-6</b> — document sequences are created lazily on first use, which is a race on a new
 *       tenant's first busy morning.</li>
 * </ul>
 *
 * <p>Bootstrap now delegates here, so the path a brand-new tenant takes is the same path the very
 * first one took — the only way to keep them from diverging.
 */
@Service
public class TenantProvisioningService {

    private static final Logger log = LoggerFactory.getLogger(TenantProvisioningService.class);

    private final OrganisationRepository organisations;
    private final CompanyRepository companies;
    private final BranchRepository branches;
    private final AppUserRepository users;
    private final UserBranchRepository userBranches;
    private final PasswordEncoder passwordEncoder;
    private final CompanyProvisioningService companyProvisioning;
    private final StockLocationSeeder stockLocationSeeder;
    private final PettyCashFundSeeder pettyCashFundSeeder;
    private final LeaveTypeSeeder leaveTypeSeeder;

    public TenantProvisioningService(OrganisationRepository organisations,
                                     CompanyRepository companies,
                                     BranchRepository branches,
                                     AppUserRepository users,
                                     UserBranchRepository userBranches,
                                     PasswordEncoder passwordEncoder,
                                     CompanyProvisioningService companyProvisioning,
                                     StockLocationSeeder stockLocationSeeder,
                                     PettyCashFundSeeder pettyCashFundSeeder,
                                     LeaveTypeSeeder leaveTypeSeeder) {
        this.organisations = organisations;
        this.companies = companies;
        this.branches = branches;
        this.users = users;
        this.userBranches = userBranches;
        this.passwordEncoder = passwordEncoder;
        this.companyProvisioning = companyProvisioning;
        this.stockLocationSeeder = stockLocationSeeder;
        this.pettyCashFundSeeder = pettyCashFundSeeder;
        this.leaveTypeSeeder = leaveTypeSeeder;
    }

    /** Everything a caller needs back after provisioning, without exposing the entities. */
    public record ProvisionedTenant(Long organisationId, Long companyId, Long branchId,
                                    Long adminUserId, String organisationName, String companyCode,
                                    String branchCode, String adminUsername) {
    }

    /** What a tenant is created from. Deliberately flat — no entity may cross this boundary. */
    public record NewTenantRequest(String organisationName, String timeZone,
                                   String companyCode, String companyName,
                                   String branchCode, String branchName,
                                   String adminUsername, String rawAdminPassword,
                                   String adminDisplayName,
                                   String baseCurrency, String defaultCurrency,
                                   java.util.List<String> enabledCurrencies) {
    }

    /**
     * Creates the tenant and everything beneath it, in ONE transaction.
     *
     * <p>All-or-nothing on purpose. A half-provisioned tenant — an organisation with no chart of
     * accounts, or a branch with no stock locations — is worse than no tenant at all: it looks
     * usable, is not, and there is no code path that repairs it.
     */
    @Transactional
    public ProvisionedTenant provision(NewTenantRequest request) {
        Organisation org = new Organisation(request.organisationName());
        org.setDefaultTimeZone(request.timeZone());
        organisations.save(org);

        Company company = new Company(org, request.companyCode(), request.companyName());
        company.setTimeZone(request.timeZone());
        companies.save(company);

        // Company-scoped defaults: UoM, tax rates, GL, AR/AP, cash/bank, inventory GL, documents,
        // fixed assets, costing dimensions, CRM stages, HR GL + statutory, notifications,
        // manufacturing GL, currency enablement (ADR-0013..0039).
        companyProvisioning.provisionDefaults(company.getId(), request.baseCurrency(),
                request.defaultCurrency(), request.enabledCurrencies());

        Branch branch = new Branch(company, request.branchCode(), request.branchName());
        branch.setTimeZone(request.timeZone());
        branch.setDefault(true);
        branches.save(branch);

        // WAREHOUSE default + in-transit OTHER locations (ADR-0028 D-4/D-5).
        stockLocationSeeder.seedDefaults(company.getId(), branch.getId(), branch.getCode());

        // Petty-cash fund: the provisionDefaults call above was a no-op until a branch existed
        // (ADR-0050 D-7 PR-B).
        pettyCashFundSeeder.seedDefaults(company.getId());

        // P5-5. V52 seeded leave_types with CROSS JOIN companies, which covers only the companies
        // that existed when that migration ran. Without this a new tenant opens HR -> Leave empty
        // and cannot record a single day of leave.
        leaveTypeSeeder.seedDefaults(company.getId());

        AppUser admin = new AppUser(
                request.adminUsername().toLowerCase(Locale.ROOT),
                passwordEncoder.encode(request.rawAdminPassword()),
                request.adminDisplayName());
        admin.setRoot(true);
        // A fresh tenant is otherwise born unattributed: V101's backfill only covers rows that
        // already existed, so the first user of a new tenant would have none (ADR-0062 P2-1).
        admin.setOrganisationId(org.getId());
        users.save(admin);

        UserBranch defaultAssignment = new UserBranch(admin.getId(), branch, admin.getId());
        defaultAssignment.markDefault();
        userBranches.save(defaultAssignment);

        log.info("Provisioned tenant: organisation '{}', company '{}', branch '{}', admin '{}'.",
                org.getName(), company.getCode(), branch.getCode(), admin.getUsername());

        return new ProvisionedTenant(org.getId(), company.getId(), branch.getId(), admin.getId(),
                org.getName(), company.getCode(), branch.getCode(), admin.getUsername());
    }
}
