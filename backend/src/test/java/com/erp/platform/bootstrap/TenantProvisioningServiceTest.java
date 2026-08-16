package com.erp.platform.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.cashbank.service.PettyCashFundSeeder;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.domain.entity.UserCompany;
import com.erp.modules.iam.domain.entity.UserRole;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.iam.repository.UserCompanyRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.parties.service.PartyCodeGenerator;
import com.erp.modules.parties.service.WalkInCustomerProvisioner;
import com.erp.modules.products.repository.PriceListRepository;
import com.erp.modules.products.service.DefaultPriceListProvisioner;
import com.erp.modules.sales.repository.PosTillRepository;
import com.erp.modules.sales.service.PosTillProvisioner;
import com.erp.modules.sales.service.SalesDepthNumberGenerator;
import com.erp.modules.stock.service.StockLocationSeeder;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit test for {@link TenantProvisioningService} — the API tenant-creation path (ADR-0062 P5-2).
 *
 * <p>Three properties are pinned here, in ascending order of what they cost to get wrong:
 * <ol>
 *   <li>values the operator supplied are applied verbatim, and blank ones create <b>nothing</b>;</li>
 *   <li>the tax identity is on the Company row <b>before</b> {@code provisionDefaults} runs, because
 *       {@code DocumentBrandingSeeder} snapshots it there exactly once and never again;</li>
 *   <li>the bootstrap-shaped request — nulls for every optional component — creates none of them,
 *       so a fresh install still produces exactly what it produced before these fields existed.</li>
 * </ol>
 *
 * <p>No Docker: every collaborator is a mock, and {@code OrganisationAlias.derive} tolerates the
 * null id of an entity a mock repository never assigned one to.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TenantProvisioningServiceTest {

    private static final long COMPANY_ID = 42L;
    private static final long BRANCH_ID = 7L;
    private static final long PRICE_LIST_ID = 77L;

    @Mock OrganisationRepository organisations;
    @Mock CompanyRepository companies;
    @Mock BranchRepository branches;
    @Mock AppUserRepository users;
    @Mock UserBranchRepository userBranches;
    @Mock UserCompanyRepository userCompanies;
    @Mock PasswordEncoder passwordEncoder;
    @Mock CompanyProvisioningService companyProvisioning;
    @Mock StockLocationSeeder stockLocationSeeder;
    @Mock PettyCashFundSeeder pettyCashFundSeeder;
    @Mock DefaultPriceListProvisioner priceListProvisioner;
    @Mock WalkInCustomerProvisioner walkInCustomerProvisioner;
    @Mock PosTillProvisioner posTillProvisioner;
    @Mock RoleRepository roles;
    @Mock UserRoleRepository userRoles;

    // Only for the bootstrap-parity test, which wires the REAL provisioners so the assertion can be
    // "no row was written" rather than "the right nulls were passed on".
    @Mock PriceListRepository priceLists;
    @Mock CustomerRepository customers;
    @Mock PartyCodeGenerator partyCodeGenerator;
    @Mock CashBankAccountRepository cashAccounts;
    @Mock PosTillRepository tills;
    @Mock SalesDepthNumberGenerator salesNumberGenerator;

    private TenantProvisioningService service;

    /** The Company instance the service built, captured the moment it was saved. */
    private final AtomicReference<Company> savedCompany = new AtomicReference<>();

    @BeforeEach
    void setUp() {
        service = new TenantProvisioningService(organisations, companies, branches, users,
                userBranches, userCompanies, passwordEncoder, companyProvisioning,
                stockLocationSeeder, pettyCashFundSeeder, priceListProvisioner,
                walkInCustomerProvisioner, posTillProvisioner, roles, userRoles);

        // provisionDefaults takes a PRIMITIVE long, so the saved company must carry an id or the
        // service unboxes a null.
        when(companies.save(any(Company.class))).thenAnswer(invocation -> {
            Company company = invocation.getArgument(0);
            ReflectionTestUtils.setField(company, "id", COMPANY_ID);
            savedCompany.set(company);
            return company;
        });
        when(branches.save(any(Branch.class))).thenAnswer(invocation -> {
            Branch branch = invocation.getArgument(0);
            ReflectionTestUtils.setField(branch, "id", BRANCH_ID);
            return branch;
        });
        when(organisations.save(any(Organisation.class))).thenAnswer(invocation -> {
            Organisation org = invocation.getArgument(0);
            ReflectionTestUtils.setField(org, "id", 1L);
            return org;
        });
        // Mirrors the real provisioner: an id when a list was asked for, null when it was not.
        // Mockito's default for a Long return is 0L, which would look like a real price list id.
        when(priceListProvisioner.createIfNamed(anyLong(), any(), any(), any())).thenAnswer(
                invocation -> invocation.getArgument(2) == null ? null : PRICE_LIST_ID);

        // ORG_ADMIN is seeded by V1 and present in every real database. It is stubbed here because
        // it is now load-bearing rather than additive: an API-provisioned administrator holds no
        // is_root, so a missing role means a tenant with no administrator, and the service refuses
        // to create one. The two tests below pin both halves of that.
        when(roles.findByCode(anyString())).thenReturn(Optional.of(new Role("ORG_ADMIN", "Org Admin")));
    }

    // -------------------------------------------------------------------------
    // Who is root — the platform tier
    // -------------------------------------------------------------------------

    /**
     * The security property this whole class exists to keep. A customer's administrator must NOT be
     * root: {@code PermissionResolver.hasPermission} returns true for a root principal before the
     * permission code is compared, so a root tenant administrator effectively holds
     * {@code ORG.CREATE} and {@code ORG.SUSPEND} — and can suspend a DIFFERENT customer's
     * organisation — however carefully the seed withholds the {@code platform} module from
     * {@code ORG_ADMIN}.
     *
     * <p>Nothing observed this before. The mock {@code AppUserRepository} was declared and never
     * captured, so {@code admin.setRoot(true)} was invisible to the entire suite.
     */
    @Test
    @DisplayName("a tenant provisioned through the API gets a NON-root administrator")
    void theApiProvisionedAdministratorIsNotRoot() {
        service.provision(request(null, null, null));

        assertThat(savedAdmin().isRoot())
                .as("root is not a degree of authority, it is a bypass — a customer's administrator "
                        + "holding it can reach across the tenant boundary")
                .isFalse();
    }

    /**
     * The other half, and the one that makes deleting the flag outright a catastrophe rather than a
     * simplification: bootstrap and the API call the SAME method, and this is the only
     * {@code setRoot} in the application. A fresh install whose {@code rootadmin} is not root has no
     * platform operator and no way to ever create one — {@code is_root} is settable through no API.
     */
    @Test
    @DisplayName("the bootstrap administrator IS root — it is the vendor's platform operator")
    void theBootstrapAdministratorStaysRoot() {
        service.provision(bootstrapShaped());

        assertThat(savedAdmin().isRoot()).isTrue();
    }

    // -------------------------------------------------------------------------
    // What the non-root administrator needs in order to run their own tenant
    // -------------------------------------------------------------------------

    /**
     * The ORG_ADMIN grant must be COMPANY-wide, not pinned to the founding branch.
     * {@code UserRoleRepository.resolvePermissionCodes} matches
     * {@code ur.branchId IS NULL OR ur.branchId = :branchId}, so a branch-pinned administrator
     * resolves the EMPTY permission set the moment they switch into the second branch they just
     * created. While the administrator was root nothing showed it; the symptom is a blank product on
     * a customer's second branch, not an error.
     */
    @Test
    @DisplayName("the ORG_ADMIN grant is company-wide, so it survives a branch switch")
    void theOrgAdminGrantIsNotPinnedToTheFoundingBranch() {
        service.provision(request(null, null, null));

        ArgumentCaptor<UserRole> captor = ArgumentCaptor.forClass(UserRole.class);
        verify(userRoles).save(captor.capture());
        assertThat(captor.getValue().getCompanyId()).isEqualTo(COMPANY_ID);
        assertThat(captor.getValue().getBranchId())
                .as("null branchId is the documented 'every branch in the company' form")
                .isNull();
    }

    /**
     * ADR-0046's membership row, for the administrator themselves.
     * {@code UserCompanyServiceImpl.isActiveMember} reads {@code user_company} and nothing else — no
     * root exemption, no fallback to {@code user_role} — and both {@code UserRoleServiceImpl.grant}
     * and {@code UserBranchServiceImpl.assign} refuse without it. Without this row the tenant's
     * administrator cannot assign THEMSELVES to a branch they open, and the only thing that would
     * repair it is {@code UserCompanyBackfill} on the next application restart.
     */
    @Test
    @DisplayName("the administrator gets their own company membership, in the same transaction")
    void theAdministratorIsMadeAMemberOfTheirOwnCompany() {
        service.provision(request(null, null, null));

        ArgumentCaptor<UserCompany> captor = ArgumentCaptor.forClass(UserCompany.class);
        verify(userCompanies).save(captor.capture());
        assertThat(captor.getValue().getCompany()).isSameAs(savedCompany.get());
    }

    // -------------------------------------------------------------------------
    // A missing ORG_ADMIN role means different things to the two callers
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("no ORG_ADMIN role → an API-provisioned tenant is refused, not half-created")
    void aMissingOrgAdminRoleRollsBackAnApiProvisionedTenant() {
        when(roles.findByCode(anyString())).thenReturn(Optional.empty());

        // The old code logged and continued, justified by "the administrator still has is_root, so
        // the tenant is usable". That sentence is false for a non-root administrator: the tenant
        // would be created with nobody able to administer it, at WARN, and nothing would go red.
        assertThatThrownBy(() -> service.provision(request(null, null, null)))
                .isInstanceOf(IllegalStateException.class)
                // Error hygiene: friendly, and carrying zero internal detail. IllegalStateException
                // is surfaced to the caller verbatim as a 409 by GlobalExceptionHandler.
                .hasMessageNotContainingAny("ORG_ADMIN", "role", "seed", "null");
    }

    @Test
    @DisplayName("no ORG_ADMIN role → a fresh install still boots, because its admin IS root")
    void aMissingOrgAdminRoleStillLetsAFreshInstallComplete() {
        when(roles.findByCode(anyString())).thenReturn(Optional.empty());

        // Refusing to start a brand-new deployment over a seed the migrations own would trade a
        // usable installation for none at all — and here the log's claim is still true.
        service.provision(bootstrapShaped());

        assertThat(savedAdmin().isRoot()).isTrue();
    }

    // -------------------------------------------------------------------------
    // Values supplied → applied
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("tax identity lands on the Company row, stripped and not case-folded")
    void taxIdentityIsAppliedToTheCompany() {
        service.provision(request("  Kilimanjaro Supermarket Limited  ", " 123-456-789 ", " 40-123456-A "));

        ArgumentCaptor<Company> captor = ArgumentCaptor.forClass(Company.class);
        verify(companies).save(captor.capture());
        Company company = captor.getValue();

        assertThat(company.getLegalName()).isEqualTo("Kilimanjaro Supermarket Limited");
        // A TIN is digits and hyphens; a Tanzanian VRN ends in a letter. Upper-casing either would
        // be inventing a rule nobody asked for, so only surrounding whitespace is removed.
        assertThat(company.getTaxId()).isEqualTo("123-456-789");
        assertThat(company.getVrn()).isEqualTo("40-123456-A");
    }

    @Test
    @DisplayName("the price list, walk-in customer and till are created from the request's values")
    void requestedExtrasAreCreatedWithTheirSuppliedNames() {
        service.provision(full("RETAIL", "Retail", "Walk-in Customer", "HQ Till 1"));

        verify(priceListProvisioner).createIfNamed(COMPANY_ID, "RETAIL", "Retail", null);
        verify(walkInCustomerProvisioner).createIfNamed(COMPANY_ID, "Walk-in Customer");
        // The till records the price list this same transaction just created — the id is never
        // supplied by the caller.
        verify(posTillProvisioner).createIfNamed(COMPANY_ID, BRANCH_ID, "HQ Till 1", PRICE_LIST_ID);
    }

    // -------------------------------------------------------------------------
    // Values blank → nothing at all
    // -------------------------------------------------------------------------

    @ParameterizedTest(name = "blank tax identity [{0}] leaves all three columns null")
    @ValueSource(strings = {"", "   "})
    void blankTaxIdentityWritesNothing(String blank) {
        service.provision(request(blank, blank, blank));

        Company company = savedCompany.get();
        assertThat(company.getLegalName()).isNull();
        assertThat(company.getTaxId()).isNull();
        assertThat(company.getVrn()).isNull();
    }

    @Test
    @DisplayName("a null tax identity leaves all three columns null")
    void nullTaxIdentityWritesNothing() {
        service.provision(request(null, null, null));

        Company company = savedCompany.get();
        assertThat(company.getLegalName()).isNull();
        assertThat(company.getTaxId()).isNull();
        assertThat(company.getVrn()).isNull();
    }

    /**
     * The VAT stance is the operator's answer, not the platform's: an EXCLUSIVE list holding
     * VAT-inclusive shelf prices adds 18% to every line, and the per-line snapshot means correcting
     * it later fixes nothing already sold. It must reach the provisioner exactly as given, including
     * the null that means "not stated".
     */
    @Test
    @DisplayName("the stated VAT stance travels to the price-list provisioner untouched")
    void theVatStanceTravelsToTheProvisioner() {
        service.provision(full("RETAIL", "Retail", null, null, Boolean.TRUE));

        verify(priceListProvisioner).createIfNamed(COMPANY_ID, "RETAIL", "Retail", Boolean.TRUE);
    }

    @Test
    @DisplayName("the three optional creations are independent: naming one creates only that one")
    void namingOnlyTheTillCreatesOnlyTheTill() {
        service.provision(full(null, null, null, "HQ Till 1"));

        verify(priceListProvisioner).createIfNamed(COMPANY_ID, null, null, null);
        verify(walkInCustomerProvisioner).createIfNamed(COMPANY_ID, null);
        // Null price list id, because none was requested — not a substituted one.
        verify(posTillProvisioner).createIfNamed(eq(COMPANY_ID), eq(BRANCH_ID), eq("HQ Till 1"),
                isNull());
    }

    @Test
    @DisplayName("naming only the walk-in customer creates only the walk-in customer")
    void namingOnlyTheWalkInCustomerCreatesOnlyTheCustomer() {
        service.provision(full(null, null, "Walk-in Customer", null));

        verify(walkInCustomerProvisioner).createIfNamed(COMPANY_ID, "Walk-in Customer");
        verify(priceListProvisioner).createIfNamed(COMPANY_ID, null, null, null);
        verify(posTillProvisioner).createIfNamed(eq(COMPANY_ID), eq(BRANCH_ID), isNull(), any());
    }

    // -------------------------------------------------------------------------
    // Ordering — the trap
    // -------------------------------------------------------------------------

    /**
     * The one test that fails if the tax-identity setters are ever moved below
     * {@code provisionDefaults}. An {@code InOrder} verify cannot catch that move: both writes land
     * on the SAME Company instance, so by the time the assertions run the field is populated either
     * way. Only reading it at the moment of the call tells the two apart.
     *
     * <p>What the move costs, if it is ever made: {@code DocumentBrandingSeeder} snapshots
     * {@code company.taxId} into {@code document_branding} once, on the pass that creates the row,
     * and never again — so the tenant's Company screen would show the TIN while every printed
     * invoice, GRN, purchase order, delivery note, credit note and proforma omitted it, for the life
     * of the company.
     */
    @Test
    @DisplayName("the TIN is on the Company BEFORE provisionDefaults snapshots it into branding")
    void taxIdentityIsSetBeforeProvisionDefaultsRuns() {
        AtomicReference<String> taxIdWhenSeedersRan = new AtomicReference<>();
        AtomicReference<String> legalNameWhenSeedersRan = new AtomicReference<>();
        doAnswer(invocation -> {
            taxIdWhenSeedersRan.set(savedCompany.get().getTaxId());
            legalNameWhenSeedersRan.set(savedCompany.get().getLegalName());
            return null;
        }).when(companyProvisioning).provisionDefaults(anyLong(), any(), any(), any());

        service.provision(request("Kilimanjaro Supermarket Limited", "123-456-789", "40-123456-A"));

        assertThat(taxIdWhenSeedersRan.get()).isEqualTo("123-456-789");
        assertThat(legalNameWhenSeedersRan.get()).isEqualTo("Kilimanjaro Supermarket Limited");
    }

    /**
     * Both foreign keys the till needs are NOT NULL: {@code branch_id} and (inside the provisioner)
     * {@code cash_bank_account_id}, which only {@code CashBankSeeder} — reached through
     * {@code provisionDefaults} — creates. A refactor that hoists the block above either one
     * compiles cleanly and fails at runtime, on a customer's machine.
     */
    @Test
    @DisplayName("the till is created after the branch and after the company defaults")
    void tillIsCreatedAfterTheBranchAndTheCompanyDefaults() {
        service.provision(full("RETAIL", "Retail", "Walk-in Customer", "HQ Till 1"));

        InOrder order = inOrder(companyProvisioning, branches, priceListProvisioner,
                posTillProvisioner);
        order.verify(companyProvisioning).provisionDefaults(anyLong(), any(), any(), any());
        order.verify(branches).save(any(Branch.class));
        // Price list before till: the till records its id.
        order.verify(priceListProvisioner).createIfNamed(anyLong(), any(), any(), any());
        order.verify(posTillProvisioner).createIfNamed(anyLong(), any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Tenant one — the path no test environment ever executes
    // -------------------------------------------------------------------------

    /**
     * Half of the fresh-install guarantee: a request with nulls throughout writes none of the
     * optional rows. The other half — that {@code BootstrapRunner} actually sends such a request —
     * is NOT asserted here, because this builds the record by hand; {@code BootstrapRunnerTest}
     * captures what that class really passes. Both are needed, and neither alone means anything:
     * this one would stay green while bootstrap started sending a price-list name, and that one
     * would stay green while a provisioner inserted a row despite being given a null.
     *
     * <p>The three provisioners are REAL here, not mocks, and the assertion is on their
     * repositories: "the platform class passed nulls" would be satisfied by a provisioner that then
     * inserted a row anyway. What must be true is that no row is written, so that is what is
     * asserted.
     */
    @Test
    @DisplayName("the bootstrap-shaped request (nulls throughout) writes none of those rows")
    void bootstrapShapedRequestCreatesNothingOptional() {
        TenantProvisioningService withRealProvisioners = new TenantProvisioningService(
                organisations, companies, branches, users, userBranches, userCompanies,
                passwordEncoder, companyProvisioning, stockLocationSeeder, pettyCashFundSeeder,
                new DefaultPriceListProvisioner(priceLists),
                new WalkInCustomerProvisioner(customers, partyCodeGenerator),
                new PosTillProvisioner(cashAccounts, tills, salesNumberGenerator),
                roles, userRoles);

        withRealProvisioners.provision(new TenantProvisioningService.NewTenantRequest(
                "Tembo Group", "Africa/Dar_es_Salaam",
                "TG", "Tembo Group Ltd",
                null, null, null,
                "HQ", "Head Office",
                "rootadmin", "RootPass12345", "Root Admin",
                "TZS", "TZS", List.of("TZS"),
                null, null, null, null, null,
                false, true));

        // Not one row, and not one sequence value burned either: a code allocated for a customer
        // that is never created leaves the tenant's first real customer as CUST-0002.
        verifyNoInteractions(priceLists, customers, partyCodeGenerator, cashAccounts, tills,
                salesNumberGenerator);

        Company company = savedCompany.get();
        assertThat(company.getLegalName()).isNull();
        assertThat(company.getTaxId()).isNull();
        assertThat(company.getVrn()).isNull();
    }

    // -------------------------------------------------------------------------

    /** The {@link com.erp.modules.iam.domain.entity.AppUser} the service built, as it was saved. */
    private AppUser savedAdmin() {
        ArgumentCaptor<AppUser> captor = ArgumentCaptor.forClass(AppUser.class);
        verify(users).save(captor.capture());
        return captor.getValue();
    }

    /**
     * What {@code BootstrapRunner} sends: nulls for every optional component, a plain username, and
     * the platform-root flag SET. {@code BootstrapRunnerTest} is what pins that this really is the
     * shape bootstrap passes; this is only the shape.
     */
    private static TenantProvisioningService.NewTenantRequest bootstrapShaped() {
        return new TenantProvisioningService.NewTenantRequest(
                "Tembo Group", "Africa/Dar_es_Salaam",
                "TG", "Tembo Group Ltd",
                null, null, null,
                "HQ", "Head Office",
                "rootadmin", "RootPass12345", "Root Admin",
                "TZS", "TZS", List.of("TZS"),
                null, null, null, null, null,
                false, true);
    }

    private static TenantProvisioningService.NewTenantRequest request(
            String legalName, String taxId, String vrn) {
        return new TenantProvisioningService.NewTenantRequest(
                "Kilimanjaro Group", "Africa/Dar_es_Salaam",
                "KS", "Kilimanjaro Supermarket",
                legalName, taxId, vrn,
                "HQ", "Head Office",
                "orgadmin", "OrgPass12345", "Org Admin",
                "TZS", "TZS", List.of("TZS"),
                null, null, null, null, null,
                true, false);
    }

    private static TenantProvisioningService.NewTenantRequest full(
            String priceListCode, String priceListName, String walkInName, String tillName) {
        return full(priceListCode, priceListName, walkInName, tillName, null);
    }

    private static TenantProvisioningService.NewTenantRequest full(
            String priceListCode, String priceListName, String walkInName, String tillName,
            Boolean priceListIncludesVat) {
        return new TenantProvisioningService.NewTenantRequest(
                "Kilimanjaro Group", "Africa/Dar_es_Salaam",
                "KS", "Kilimanjaro Supermarket",
                null, null, null,
                "HQ", "Head Office",
                "orgadmin", "OrgPass12345", "Org Admin",
                "TZS", "TZS", List.of("TZS"),
                priceListCode, priceListName, priceListIncludesVat, walkInName, tillName,
                true, false);
    }
}
