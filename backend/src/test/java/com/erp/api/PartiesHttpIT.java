package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.erp.modules.iam.domain.dto.GrantRoleRequest;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Permission;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.PermissionRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.iam.service.UserRoleService;
import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.dto.CustomerDto;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.CustomerService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * HTTP-level integration tests for the Parties module controllers (ADR-0006 D-10/D-11).
 *
 * <p>The Parties list endpoints follow the Audit controller convention: they build
 * {@code ApiResponse.ok(content, PageMeta.from(page))}, so {@code $.data} is the row array and
 * {@code $.meta} carries paging ({@code page, size, totalElements, totalPages, hasNext}). Ids cross
 * the wire as strings (Long-as-string convention, ADR-0001 D-G).
 *
 * <p>Covers:
 * <ul>
 *   <li>GET /customers without CUSTOMER.VIEW → 403 envelope.
 *   <li>Root → 200 with {@code $.data} (page rows) and {@code $.meta} (paging).
 *   <li>Non-root granted CUSTOMER.VIEW → can list; granted MANAGE → can create (201).
 *   <li>Per-company scope: companyA-scoped list does not leak companyB customers.
 *   <li>POST create with MANAGE → 201; archive own company → 204; archive other-company uid → 403.
 *   <li>Branch-assign endpoint: no PARTY.BRANCH.ASSIGN → 403; with it → 201.
 *   <li>Supplier gate smoke: no SUPPLIER.VIEW → 403; root → 200.
 *   <li>Agent gate smoke: no AGENT.VIEW → 403; root → 200.
 * </ul>
 */
@AutoConfigureMockMvc
class PartiesHttpIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;

    @Autowired private IamTestData testData;
    @Autowired private PermissionResolver permissionResolver;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PermissionRepository permissions;
    @Autowired private UserBranchRepository userBranches;

    @Autowired private UserRoleService userRoleService;
    @Autowired private CustomerService customerService;
    @Autowired private JwtService jwtService;
    @Autowired private PasswordEncoder passwordEncoder;

    private Organisation org;
    private Company companyA;
    private Company companyB;
    private Branch branchA;
    private Branch branchB;

    private AppUser rootUser;
    private AppUser plainUser;

    private String rootToken;
    private String plainToken;

    private static final String ROOT_PASS  = "RootPartiesH1";
    private static final String PLAIN_PASS = "PlainPartiesH1";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        org      = organisations.save(new Organisation("Parties HTTP Org"));
        companyA = companies.save(new Company(org, "PHCA", "Parties HTTP Co A"));
        companyB = companies.save(new Company(org, "PHCB", "Parties HTTP Co B"));

        branchA = new Branch(companyA, "PH-A1", "Parties A HQ");
        branchA.setDefault(true);
        branchA = branches.save(branchA);

        branchB = new Branch(companyB, "PH-B1", "Parties B HQ");
        branchB.setDefault(true);
        branchB = branches.save(branchB);

        rootUser = new AppUser("ph_root", passwordEncoder.encode(ROOT_PASS), "PH Root");
        rootUser.setRoot(true);
        rootUser.setOrganisationId(org.getId());
        rootUser = users.save(rootUser);
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branchA, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        plainUser = new AppUser("ph_plain", passwordEncoder.encode(PLAIN_PASS), "PH Plain");
        plainUser.setOrganisationId(org.getId());
        plainUser = users.save(plainUser);
        UserBranch plainAssign = new UserBranch(plainUser.getId(), branchA, rootUser.getId());
        plainAssign.markDefault();
        userBranches.save(plainAssign);

        rootToken  = jwtService.issueAccessToken(rootUser,  companyA.getId(), branchA.getId()).value();
        plainToken = jwtService.issueAccessToken(plainUser, companyA.getId(), branchA.getId()).value();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ===================================================================
    // Test 1 — No CUSTOMER.VIEW -> GET /customers -> 403 envelope
    // ===================================================================

    @Test
    void listCustomers_noPermission_returns403Envelope() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .param("companyId", companyA.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray())
                .andExpect(jsonPath("$.errors[0]").isString());
    }

    // ===================================================================
    // Test 2 — Root bypasses gate -> 200; $.data is the Page object
    //          (CustomerController returns ApiResponse.ok(content, PageMeta.from(page)) like AuditController).
    // ===================================================================

    @Test
    void listCustomers_asRoot_returns200WithPageShape() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .param("companyId", companyA.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                // List endpoints follow the AuditController convention: data = the row array,
                // meta = PageMeta paging (totalElements is an int -> a JSON number).
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.meta.totalElements").isNumber())
                .andExpect(jsonPath("$.meta.page").isNumber())
                .andExpect(jsonPath("$.meta.hasNext").isBoolean());
    }

    // ===================================================================
    // Test 3 — Non-root with CUSTOMER.VIEW granted -> 200
    // ===================================================================

    @Test
    void listCustomers_afterGrantCustomerView_returns200() throws Exception {
        grantRoleAsRoot(plainUser, buildRole("PH_CUST_VIEWER", "CUSTOMER.VIEW"));

        mockMvc.perform(get("/api/v1/customers")
                        .param("companyId", companyA.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===================================================================
    // Test 4 — POST /customers with CUSTOMER.MANAGE -> 201, $.data.code = CUST-0001
    //          POST body uses the typed DTO serialised by ObjectMapper — avoids the
    //          Long-as-string Jackson config turning companyId into "1" (a string),
    //          which would fail deserialization into @NotNull Long companyId.
    // ===================================================================

    @Test
    void createCustomer_withManagePermission_returns201AndCodeCust0001() throws Exception {
        grantRoleAsRoot(plainUser, buildRole("PH_CUST_MANAGER", "CUSTOMER.VIEW", "CUSTOMER.MANAGE"));

        String body = objectMapper.writeValueAsString(new CreateCustomerRequest(
                companyA.getId(), PartyType.BUSINESS, "HTTP Created Co", null,
                "TIN-HTTP1", false, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));

        mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.code").value("CUST-0001"))
                .andExpect(jsonPath("$.data.uid").isString())
                // ids cross the wire as strings (Long-as-string convention, ADR-0001 D-G).
                .andExpect(jsonPath("$.data.companyId").isString());
    }

    // ===================================================================
    // Test 5 — Per-company scope: list for co A does NOT expose co B customers
    // ===================================================================

    @Test
    void listCustomers_scopedToCompanyA_doesNotSeeCompanyBCustomers() throws Exception {
        seedCustomerViaService(companyA.getId(), branchA.getId(), "Co A Customer", "TIN-SCOPE-A");
        seedCustomerViaService(companyB.getId(), branchB.getId(), "Co B Customer", "TIN-SCOPE-B");

        grantRoleAsRoot(plainUser, buildRole("PH_SCOPE_VIEWER", "CUSTOMER.VIEW"));

        mockMvc.perform(get("/api/v1/customers")
                        .param("companyId", companyA.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data[?(@.displayName == 'Co A Customer')]").exists())
                .andExpect(jsonPath("$.data[?(@.displayName == 'Co B Customer')]").doesNotExist());
    }

    // ===================================================================
    // Test 6 — @perm.scoped archive: own company -> 204; other-company uid -> 403
    // ===================================================================

    @Test
    void archiveCustomer_ownCompany_returns204() throws Exception {
        grantRoleAsRoot(plainUser, buildRole("PH_CUST_MGR2", "CUSTOMER.VIEW", "CUSTOMER.MANAGE"));

        String body = objectMapper.writeValueAsString(new CreateCustomerRequest(
                companyA.getId(), PartyType.BUSINESS, "Archive Target", null,
                "TIN-ARCH1", false, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));

        String responseJson = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String customerUid = objectMapper.readTree(responseJson).path("data").path("uid").asText();

        mockMvc.perform(put("/api/v1/customers/uid/" + customerUid + "/archive")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isNoContent());
    }

    @Test
    void archiveCustomer_otherCompanyUid_returns403() throws Exception {
        grantRoleAsRoot(plainUser, buildRole("PH_CUST_MGR3", "CUSTOMER.VIEW", "CUSTOMER.MANAGE"));

        CustomerDto companyBCustomer = seedCustomerViaService(
                companyB.getId(), branchB.getId(), "B Scope Target", "TIN-BSCOPE");

        mockMvc.perform(put("/api/v1/customers/uid/" + companyBCustomer.uid() + "/archive")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // ===================================================================
    // Test 7 — Branch assign: no PARTY.BRANCH.ASSIGN -> 403; with it -> 201
    // ===================================================================

    @Test
    void assignBranch_withoutPermission_returns403() throws Exception {
        grantRoleAsRoot(plainUser, buildRole("PH_VIEW_ONLY2", "CUSTOMER.VIEW"));

        CustomerDto customer = seedCustomerViaService(
                companyA.getId(), branchA.getId(), "Branch Assign Target", "TIN-BAT");

        mockMvc.perform(post("/api/v1/customers/uid/" + customer.uid() + "/branches")
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AssignPartyBranchRequest(branchA.getUid()))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void assignBranch_withPartyBranchAssignPermission_returns201() throws Exception {
        grantRoleAsRoot(plainUser, buildRole("PH_BRANCH_ASSIGNER",
                "CUSTOMER.VIEW", "CUSTOMER.MANAGE", "PARTY.BRANCH.ASSIGN"));

        String body = objectMapper.writeValueAsString(new CreateCustomerRequest(
                companyA.getId(), PartyType.BUSINESS, "Branch Assign OK", null,
                "TIN-BAOK", false, null, null, null, null, null, null, null, null, null,
                CustomerKind.CREDIT_ACCOUNT, null, null, null));

        String responseJson = mockMvc.perform(post("/api/v1/customers")
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String customerUid = objectMapper.readTree(responseJson).path("data").path("uid").asText();

        mockMvc.perform(post("/api/v1/customers/uid/" + customerUid + "/branches")
                        .header("Authorization", "Bearer " + plainToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(
                                new AssignPartyBranchRequest(branchA.getUid()))))
                .andExpect(status().isCreated())
                // branchId is a Long -> string on the wire (Long-as-string convention).
                .andExpect(jsonPath("$.data.branchId").isString());
    }

    // ===================================================================
    // Security regression tests (findings 1–3): cross-company read attacks must be denied
    // ===================================================================

    /**
     * Finding 1 regression: non-root user in company A with CUSTOMER.VIEW passes companyId=B
     * → must get 403, not company B's rows.
     */
    @Test
    void listCustomers_crossCompanyId_nonRootUser_returns403() throws Exception {
        grantRoleAsRoot(plainUser, buildRole("SEC_CUST_VIEWER", "CUSTOMER.VIEW"));

        // plainToken is scoped to companyA; querying companyB's id must be denied.
        mockMvc.perform(get("/api/v1/customers")
                        .param("companyId", companyB.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray());
    }

    /**
     * Finding 2 regression: non-root user in company A with CUSTOMER.VIEW fetches
     * GET /customers/uid/{uid} where uid belongs to company B → must get 403.
     */
    @Test
    void getCustomerByUid_crossCompany_nonRootUser_returns403() throws Exception {
        grantRoleAsRoot(plainUser, buildRole("SEC_CUST_GET_VIEWER", "CUSTOMER.VIEW"));

        CustomerDto companyBCustomer = seedCustomerViaService(
                companyB.getId(), branchB.getId(), "B Get Target", "TIN-BGET");

        // plainToken scoped to companyA — reading a companyB uid must be denied.
        mockMvc.perform(get("/api/v1/customers/uid/" + companyBCustomer.uid())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray());
    }

    /**
     * Finding 3 regression: non-root user in company A with CUSTOMER.VIEW requests
     * GET /customers/uid/{uid}/branches where uid belongs to company B → must get 403.
     */
    @Test
    void listCustomerBranches_crossCompany_nonRootUser_returns403() throws Exception {
        grantRoleAsRoot(plainUser, buildRole("SEC_CUST_BRANCH_VIEWER", "CUSTOMER.VIEW"));

        CustomerDto companyBCustomer = seedCustomerViaService(
                companyB.getId(), branchB.getId(), "B Branch Target", "TIN-BBR");

        // plainToken scoped to companyA — listing branches of a companyB customer must be denied.
        mockMvc.perform(get("/api/v1/customers/uid/" + companyBCustomer.uid() + "/branches")
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // ===================================================================
    // Test 8 — Supplier gate smoke
    // ===================================================================

    @Test
    void listSuppliers_noPermission_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers")
                        .param("companyId", companyA.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void listSuppliers_asRoot_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers")
                        .param("companyId", companyA.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===================================================================
    // Test 9 — Agent gate smoke
    // ===================================================================

    @Test
    void listAgents_noPermission_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/agents")
                        .param("companyId", companyA.getId().toString())
                        .header("Authorization", "Bearer " + plainToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.data").doesNotExist())
                .andExpect(jsonPath("$.errors").isArray());
    }

    @Test
    void listAgents_asRoot_returns200() throws Exception {
        mockMvc.perform(get("/api/v1/agents")
                        .param("companyId", companyA.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    // ===================================================================
    // Private helpers
    // ===================================================================

    private Role buildRole(String code, String... permissionCodes) {
        Role role = new Role(code, code);
        Set<Permission> perms = new HashSet<>();
        for (String pc : permissionCodes) {
            perms.add(permissions.findByCode(pc)
                    .orElseThrow(() -> new IllegalStateException(
                            pc + " must be seeded by Flyway migration")));
        }
        role.setPermissions(perms);
        return roles.save(role);
    }

    private void grantRoleAsRoot(AppUser user, Role role) {
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                companyA.getId(), branchA.getId(), null));
        try {
            testData.seedMembership(user.getUid(), companyA.getUid());
            userRoleService.grant(new GrantRoleRequest(
                    user.getUid(), role.getUid(), companyA.getUid(), null));
            permissionResolver.invalidate();
        } finally {
            RequestContext.clear();
        }
    }

    private CustomerDto seedCustomerViaService(Long companyId, Long branchId,
                                               String name, String tin) {
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true, companyId, branchId, null));
        try {
            return customerService.create(new CreateCustomerRequest(
                    companyId, PartyType.BUSINESS, name, null,
                    tin, false, null, null, null, null, null, null, null, null, null,
                    CustomerKind.CREDIT_ACCOUNT, null, null, null));
        } finally {
            RequestContext.clear();
        }
    }
}
