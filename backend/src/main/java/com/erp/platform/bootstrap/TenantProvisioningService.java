package com.erp.platform.bootstrap;

import com.erp.modules.cashbank.service.PettyCashFundSeeder;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.domain.entity.UserRole;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.parties.service.WalkInCustomerProvisioner;
import com.erp.modules.products.service.DefaultPriceListProvisioner;
import com.erp.modules.sales.service.PosTillProvisioner;
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
 *
 * <h2>What this service creates that {@code provisionDefaults} does not</h2>
 *
 * The tax identity, the first price list, the counter customer and the first POS till. All four are
 * built from values the OPERATOR supplied on the request, and a blank one creates nothing at all —
 * there is no default, because a price list named "Standard" or a TIN chosen in Java is a wrong
 * answer nobody can tell from a right one.
 *
 * <p>They are here rather than in {@code CompanyProvisioningServiceImpl.provisionDefaults} because
 * that method is also the HEAL path: {@code POST /api/v1/companies/uid/{uid}/provision-defaults}
 * runs it against companies that have traded for years. Nothing in this block is reachable from
 * there, so re-provisioning a live company writes none of them — the risk is designed out rather
 * than guarded against, and the "did the idempotency guard hold?" question never has to be answered.
 * {@code TenantOnlyProvisionersTest} keeps it that way.
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
    // The three request-driven creations. NOT seeders, and deliberately not in the seeder chain —
    // see the class javadoc's "What this service creates that provisionDefaults does not".
    private final DefaultPriceListProvisioner priceListProvisioner;
    private final WalkInCustomerProvisioner walkInCustomerProvisioner;
    private final PosTillProvisioner posTillProvisioner;
    private final com.erp.modules.iam.repository.RoleRepository roles;
    private final com.erp.modules.iam.repository.UserRoleRepository userRoles;

    /** Seeded by V1__baseline.sql; the tenant administrator bundle. */
    private static final String ORG_ADMIN_CODE = "ORG_ADMIN";

    public TenantProvisioningService(OrganisationRepository organisations,
                                     CompanyRepository companies,
                                     BranchRepository branches,
                                     AppUserRepository users,
                                     UserBranchRepository userBranches,
                                     PasswordEncoder passwordEncoder,
                                     CompanyProvisioningService companyProvisioning,
                                     StockLocationSeeder stockLocationSeeder,
                                     PettyCashFundSeeder pettyCashFundSeeder,
                                     DefaultPriceListProvisioner priceListProvisioner,
                                     WalkInCustomerProvisioner walkInCustomerProvisioner,
                                     PosTillProvisioner posTillProvisioner,
                                     com.erp.modules.iam.repository.RoleRepository roles,
                                     com.erp.modules.iam.repository.UserRoleRepository userRoles) {
        this.organisations = organisations;
        this.companies = companies;
        this.branches = branches;
        this.users = users;
        this.userBranches = userBranches;
        this.passwordEncoder = passwordEncoder;
        this.companyProvisioning = companyProvisioning;
        this.stockLocationSeeder = stockLocationSeeder;
        this.pettyCashFundSeeder = pettyCashFundSeeder;
        this.priceListProvisioner = priceListProvisioner;
        this.walkInCustomerProvisioner = walkInCustomerProvisioner;
        this.posTillProvisioner = posTillProvisioner;
        this.roles = roles;
        this.userRoles = userRoles;
    }

    /** Everything a caller needs back after provisioning, without exposing the entities. */
    public record ProvisionedTenant(Long organisationId, Long companyId, Long branchId,
                                    Long adminUserId, String organisationName, String companyCode,
                                    String branchCode, String adminUsername) {
    }

    /**
     * What a tenant is created from. Deliberately flat — no entity may cross this boundary.
     *
     * <h2>Why there is no shorter convenience constructor</h2>
     *
     * Widening this record breaks its two call sites, and that break is the safety property, not a
     * cost to engineer away. A secondary constructor that fills the newer components with null is
     * precisely the defect commit 964e467b had to fix: {@code baseCurrency} was captured on
     * {@code CreateTenantRequest} in P5-2, silently never applied, and every tenant provisioned as
     * KES posted its whole ledger in TZS with nothing anywhere going red. Two lines of compile
     * error, once, is the cheaper half of that trade.
     *
     * <p>The trailing group — price list, walk-in customer, till, and the three tax-identity
     * components — is OPTIONAL in the strict sense: a null or blank one creates <b>nothing</b>.
     * There is no fallback and no default. {@code BootstrapRunner} passes null for every one of
     * them, so a fresh install produces exactly what it produced before they existed.
     */
    public record NewTenantRequest(String organisationName, String timeZone,
                                   String companyCode, String companyName,
                                   String companyLegalName, String companyTaxId, String companyVrn,
                                   String branchCode, String branchName,
                                   String adminUsername, String rawAdminPassword,
                                   String adminDisplayName,
                                   String baseCurrency, String defaultCurrency,
                                   java.util.List<String> enabledCurrencies,
                                   String priceListCode, String priceListName,
                                   Boolean priceListIncludesVat,
                                   String walkInCustomerName, String posTillName,
                                   /**
                                    * Whether the administrator's username gets the {@code @alias}
                                    * suffix (D-7).
                                    *
                                    * <p>TRUE for a tenant provisioned through the API: two customers
                                    * each asking for an {@code admin} would otherwise collide on the
                                    * globally-unique username column.
                                    *
                                    * <p>FALSE for bootstrap. {@code dist/bundle/.env.example:101} and
                                    * {@code INSTALL.md:191} both promise the first administrator is
                                    * "always rootadmin", and a new customer follows those to their
                                    * first login. Composing it would break that promise on every
                                    * fresh install, for a uniqueness problem a deployment's founding
                                    * account does not have.
                                    */
                                   boolean composeAdminUsername) {
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

        // The alias must exist the moment the tenant does. TenancyReconciler back-fills it on the
        // NEXT boot, which is too late: the alias is the `@alias` half of every username created
        // under this organisation (D-7), so a tenant provisioned at 09:00 and given staff at 09:05
        // would issue BARE usernames until someone restarted the application - and those names are
        // never rewritten (§0.2), leaving one tenant permanently split across two formats.
        // Derived through the shared helper so provisioning and the reconciler cannot disagree.
        org.setAlias(OrganisationAlias.derive(org.getName(), org.getId()));
        organisations.save(org);

        Company company = new Company(org, request.companyCode(), request.companyName());
        company.setTimeZone(request.timeZone());

        // The ledger currency has to land on the COMPANY ROW, not just travel to the currency
        // seeder. Company.baseCurrency initialises to "TZS", and roughly forty read paths resolve
        // the posting currency as companies.findById(id).map(Company::getBaseCurrency) — so without
        // this a tenant provisioned as KES gets KES enablement rows and posts its entire ledger in
        // TZS. Nothing would fail: the numbers are simply relabelled, on every document, from the
        // first day, and the only way to notice is to know what the total should have been.
        //
        // CompanyServiceImpl.create has always taken the base FROM the saved company for exactly
        // this reason. This is the tenant path being made to agree with it.
        if (request.baseCurrency() != null && !request.baseCurrency().isBlank()) {
            company.setBaseCurrency(request.baseCurrency().strip().toUpperCase(Locale.ROOT));
        }

        // TAX IDENTITY MUST LAND HERE, BEFORE provisionDefaults BELOW. That call reaches
        // DocumentBrandingSeeder, which copies company.legalName and company.taxId into the
        // document_branding row ONCE, on the only pass where the row is absent, and never again for
        // the life of the company. Set these after it and the branding row is written with nulls
        // permanently: the Company screen would show the TIN while every printed invoice, GRN,
        // purchase order, delivery note, credit note and proforma omitted it, repairable only from
        // the Document Branding screen or by hand.
        //
        // Blank means the operator did not know it, and the answer to that is to record nothing —
        // never a placeholder. All three columns are nullable, and every reader guards on null, so
        // an unset TIN prints no TIN line rather than an empty label. Stripped but NOT case-folded:
        // a TIN is digits and hyphens and a VRN can end in a letter.
        if (request.companyLegalName() != null && !request.companyLegalName().isBlank()) {
            company.setLegalName(request.companyLegalName().strip());
        }
        if (request.companyTaxId() != null && !request.companyTaxId().isBlank()) {
            company.setTaxId(request.companyTaxId().strip());
        }
        if (request.companyVrn() != null && !request.companyVrn().isBlank()) {
            company.setVrn(request.companyVrn().strip());
        }
        companies.save(company);

        // Company-scoped defaults: UoM, tax rates, GL, AR/AP, cash/bank, inventory GL, documents,
        // fixed assets, costing dimensions, CRM stages, HR GL + statutory, notifications,
        // manufacturing GL, currency enablement (ADR-0013..0039).
        //
        // Read back from the entity rather than re-using the request, so the enablement rows and
        // the company row cannot disagree even if the normalisation above changes.
        companyProvisioning.provisionDefaults(company.getId(), company.getBaseCurrency(),
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

        // The three things a tenant cannot trade without, from values the operator supplied; a blank
        // one creates nothing (see the class javadoc for why none of this is a seeder).
        //
        // Ordering is load-bearing: after provisionDefaults, which creates the party/code sequences
        // these allocate from and the cash account the till's NOT NULL drawer FK points at; after
        // the branch, because pos_tills.branch_id is NOT NULL; and price list before till, which
        // records its id.
        Long priceListId = priceListProvisioner.createIfNamed(
                company.getId(), request.priceListCode(), request.priceListName(),
                request.priceListIncludesVat());
        walkInCustomerProvisioner.createIfNamed(company.getId(), request.walkInCustomerName());
        posTillProvisioner.createIfNamed(
                company.getId(), branch.getId(), request.posTillName(), priceListId);

        // The tenant's first administrator is a TENANT user, so their username composes like every
        // other one (D-7): the caller supplies the local part and the alias is appended here. Without
        // this, two tenants each provisioning an `admin` would collide on the globally-unique
        // username column - and the second provisioning would fail on a constraint rather than on
        // anything the platform operator did wrong.
        String adminLocal = request.adminUsername().toLowerCase(Locale.ROOT).trim();
        if (adminLocal.indexOf('@') >= 0) {
            throw new IllegalArgumentException("The administrator username must not contain '@'.");
        }
        String adminUsername =
                !request.composeAdminUsername() || org.getAlias() == null || org.getAlias().isBlank()
                        ? adminLocal
                        : adminLocal + "@" + org.getAlias();

        AppUser admin = new AppUser(
                adminUsername,
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

        // P4-3 (ADR-0062): give the tenant's administrator ORG_ADMIN as a ROLE, not only the
        // is_root flag. Additive on purpose - is_root is left exactly as it was, so nothing this
        // release does can leave a tenant with nobody able to administer it.
        //
        // It matters because of where the model is going: rootadmin becomes the PLATFORM account,
        // and each tenant gets its own orgadmin. A tenant whose only administrator is is_root has
        // no administrator at all once that lands, and usernames are never rewritten, so retro-
        // fitting the grant later means touching every tenant provisioned before the change.
        // Granting it now costs one row and makes that transition a flag flip.
        //
        // A missing ORG_ADMIN row is logged rather than thrown: it is seeded by V1 and cannot be
        // absent in practice, but failing tenant creation over a missing grant would trade a
        // complete tenant for none at all.
        roles.findByCode(ORG_ADMIN_CODE).ifPresentOrElse(
                orgAdmin -> userRoles.save(new UserRole(
                        admin.getId(), orgAdmin, company.getId(), branch.getId(), admin.getId())),
                () -> log.warn("Provisioned tenant {} without an ORG_ADMIN grant: the role is not "
                        + "seeded. The administrator still has is_root, so the tenant is usable, "
                        + "but grant it once the seed is repaired.", org.getName()));

        log.info("Provisioned tenant: organisation '{}', company '{}', branch '{}', admin '{}'.",
                org.getName(), company.getCode(), branch.getCode(), admin.getUsername());

        return new ProvisionedTenant(org.getId(), company.getId(), branch.getId(), admin.getId(),
                org.getName(), company.getCode(), branch.getCode(), admin.getUsername());
    }
}
