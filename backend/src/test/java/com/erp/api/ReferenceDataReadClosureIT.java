package com.erp.api;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Regression guard for ISSUE-008: a non-root user who holds a primary operational permission (e.g.
 * {@code AP.BILL.ENTER}) but none of the supporting reference-data {@code *.VIEW} codes must NOT
 * receive a 403 from the picker endpoints their screen fires on load.
 *
 * <p>The root cause was a read-closure gap in role grants: narrowly-scoped operational roles omit
 * supporting reads ({@code BRANCH.VIEW}, {@code PRODUCT.VIEW}, {@code WHT.VIEW},
 * {@code PURCHASE.ORDER.VIEW}), so reference-data pickers hard-403'd and blanked the screen. The
 * fix (2026-06-28 sim) introduced {@code @perm.hasOrMember} / {@code @perm.scopedOrMember} read
 * floors at the GATE layer without touching grants or migrations.
 *
 * <p>What these tests assert:
 * <ol>
 *   <li>A non-root user with only {@code AP.BILL.ENTER} can now GET branches, products, WHT types,
 *       and the PO list — all of which their bill-entry screen depends on.</li>
 *   <li>A non-member (zero grants, unprovisioned) is still 403'd on every picker.</li>
 *   <li>Tenant isolation holds: the same user cannot read ANOTHER company's branches even after
 *       the gate relaxation (the company-scope check in {@code scopedOrMember} blocks it).</li>
 * </ol>
 *
 * <p>Tests run through the REAL Spring Security filter chain against a real Testcontainers Postgres
 * (no mocked DB — qa-engineer's standing rule). They are intentionally narrow: deep business logic
 * is covered by the respective service ITs; here we verify only that the GATE passes or 403s
 * correctly for the RBAC change.
 */
@AutoConfigureMockMvc
class ReferenceDataReadClosureIT extends PostgresIntegrationTest {

    @Autowired private MockMvc             mockMvc;
    @Autowired private IamTestData         testData;
    @Autowired private PermissionResolver  permissionResolver;

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository      companies;
    @Autowired private BranchRepository       branches;
    @Autowired private AppUserRepository      users;
    @Autowired private RoleRepository         roles;
    @Autowired private PermissionRepository   permissions;
    @Autowired private UserBranchRepository   userBranches;
    @Autowired private UserRoleService        userRoleService;
    @Autowired private JwtService             jwtService;
    @Autowired private PasswordEncoder        passwordEncoder;

    private Organisation org;
    private Company      company;
    private Company      otherCompany;
    private Branch       branch;
    private Branch       otherBranch;
    private AppUser      rootUser;
    /** Has only AP.BILL.ENTER (no *.VIEW codes). Represents a typical AP-clerk operational role. */
    private AppUser      apClerkUser;
    /** Has zero grants — a freshly created user with no role at all. */
    private AppUser      nonMemberUser;
    /**
     * Has CUSTOMER.VIEW (and nothing else). Proves the gate admits a proper holder
     * and anchors the F22 decision: customer/supplier gates are NOT relaxed to a member
     * read-floor — they stay permission-gated by design.
     */
    private AppUser      customerViewUser;
    /**
     * Has SUPPLIER.VIEW (and nothing else). Mirror of customerViewUser for the supplier gate.
     */
    private AppUser      supplierViewUser;
    private String       rootToken;
    private String       apClerkToken;
    private String       nonMemberToken;
    private String       customerViewToken;
    private String       supplierViewToken;

    private static final String ROOT_PASS           = "RdrcRootH1!";
    private static final String AP_CLERK_PASS       = "RdrcClrkH1!";
    private static final String NO_ROLE_PASS        = "RdrcNoneH1!";
    private static final String CUST_VIEW_PASS      = "RdrcCustH1!";
    private static final String SUPP_VIEW_PASS      = "RdrcSuppH1!";

    @BeforeEach
    void setUp() {
        testData.clearAll();
        permissionResolver.invalidate();

        org          = organisations.save(new Organisation("RDRC Test Org"));
        company      = companies.save(new Company(org, "RDRC",  "RDRC Company A"));
        otherCompany = companies.save(new Company(org, "RDRCB", "RDRC Company B"));

        branch = new Branch(company, "RDRC-HQ", "RDRC HQ");
        branch.setDefault(true);
        branch = branches.save(branch);

        otherBranch = new Branch(otherCompany, "RDRCB-HQ", "RDRCB HQ");
        otherBranch.setDefault(true);
        otherBranch = branches.save(otherBranch);

        // Root user
        rootUser = new AppUser("rdrc_root", passwordEncoder.encode(ROOT_PASS), "RDRC Root");
        rootUser.setRoot(true);
        rootUser = users.save(rootUser);
        UserBranch rootAssign = new UserBranch(rootUser.getId(), branch, rootUser.getId());
        rootAssign.markDefault();
        userBranches.save(rootAssign);

        // AP clerk: has AP.BILL.ENTER but deliberately NO *.VIEW read codes.
        apClerkUser = new AppUser("rdrc_apclerk", passwordEncoder.encode(AP_CLERK_PASS), "RDRC AP Clerk");
        apClerkUser = users.save(apClerkUser);
        UserBranch clerkAssign = new UserBranch(apClerkUser.getId(), branch, rootUser.getId());
        clerkAssign.markDefault();
        userBranches.save(clerkAssign);

        // Non-member: no grants, no roles at all.
        nonMemberUser = new AppUser("rdrc_nobody", passwordEncoder.encode(NO_ROLE_PASS), "RDRC Nobody");
        nonMemberUser = users.save(nonMemberUser);
        UserBranch noneAssign = new UserBranch(nonMemberUser.getId(), branch, rootUser.getId());
        noneAssign.markDefault();
        userBranches.save(noneAssign);

        // Customer-view user: CUSTOMER.VIEW only. Verifies the gate passes for a proper holder.
        customerViewUser = new AppUser("rdrc_custview", passwordEncoder.encode(CUST_VIEW_PASS), "RDRC Cust Viewer");
        customerViewUser = users.save(customerViewUser);
        UserBranch custAssign = new UserBranch(customerViewUser.getId(), branch, rootUser.getId());
        custAssign.markDefault();
        userBranches.save(custAssign);

        // Supplier-view user: SUPPLIER.VIEW only. Verifies the gate passes for a proper holder.
        supplierViewUser = new AppUser("rdrc_suppview", passwordEncoder.encode(SUPP_VIEW_PASS), "RDRC Supp Viewer");
        supplierViewUser = users.save(supplierViewUser);
        UserBranch suppAssign = new UserBranch(supplierViewUser.getId(), branch, rootUser.getId());
        suppAssign.markDefault();
        userBranches.save(suppAssign);

        // Issue the clerk a role containing ONLY AP.BILL.ENTER.
        grantRoleAsRoot(apClerkUser, buildRole("RDRC_AP_CLERK_ROLE", "AP.BILL.ENTER"));
        // Issue customer/supplier view roles — single-permission, no manage codes.
        grantRoleAsRoot(customerViewUser, buildRole("RDRC_CUST_VIEW_ROLE", "CUSTOMER.VIEW"));
        grantRoleAsRoot(supplierViewUser, buildRole("RDRC_SUPP_VIEW_ROLE", "SUPPLIER.VIEW"));

        rootToken          = jwtService.issueAccessToken(rootUser,         company.getId(), branch.getId()).value();
        apClerkToken       = jwtService.issueAccessToken(apClerkUser,      company.getId(), branch.getId()).value();
        nonMemberToken     = jwtService.issueAccessToken(nonMemberUser,    company.getId(), branch.getId()).value();
        customerViewToken  = jwtService.issueAccessToken(customerViewUser, company.getId(), branch.getId()).value();
        supplierViewToken  = jwtService.issueAccessToken(supplierViewUser, company.getId(), branch.getId()).value();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // GET /api/v1/branches — scopedOrMember gate
    // ISSUE-004/005: AP clerk (member) must not be 403'd on the branch picker.
    // =========================================================================

    @Test
    void apClerk_withOnlyApBillEnter_canListBranches() throws Exception {
        // AP clerk holds AP.BILL.ENTER but NOT BRANCH.VIEW. The branch picker their
        // bill-entry screen fires on load must return 200 (member read floor).
        // The ResponseBodyAdvice wraps the raw List<BranchDto> in ApiResponse, so the
        // array lives at $.data, not $).
        mockMvc.perform(get("/api/v1/branches")
                        .param("companyUid", company.getUid())
                        .header("Authorization", "Bearer " + apClerkToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void nonMember_noBranchViewGrant_listBranches_returns403() throws Exception {
        // Zero grants → isMember is false → scopedOrMember fails closed.
        mockMvc.perform(get("/api/v1/branches")
                        .param("companyUid", company.getUid())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    /**
     * Tenant isolation: the AP clerk is a member of {@code company} (Company A) but has no
     * relationship to {@code otherCompany} (Company B). Even after the read-floor relaxation,
     * they must receive a 403 when they address Company B's uid — {@code scopedOrMember} runs
     * {@link com.erp.platform.security.ScopeGuard#canActOn} on the target uid, and the company
     * mismatch blocks the request.
     */
    @Test
    void apClerk_memberOfCompanyA_cannotListBranchesOfCompanyB() throws Exception {
        mockMvc.perform(get("/api/v1/branches")
                        .param("companyUid", otherCompany.getUid())
                        .header("Authorization", "Bearer " + apClerkToken))
                .andExpect(status().isForbidden());
    }

    // =========================================================================
    // GET /api/v1/products — hasOrMember gate
    // ISSUE-003/005/006: manufacturing/stock/sales screens must not 403 an operational user.
    // =========================================================================

    @Test
    void apClerk_withOnlyApBillEnter_canListProducts() throws Exception {
        // AP clerk holds AP.BILL.ENTER but NOT PRODUCT.VIEW. The product picker on their
        // bill-entry screen must return 200 (member read floor).
        mockMvc.perform(get("/api/v1/products")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + apClerkToken))
                .andExpect(status().isOk());
    }

    @Test
    void nonMember_noProductViewGrant_listProducts_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // =========================================================================
    // GET /api/v1/wht/types — hasOrMember gate
    // ISSUE-001: finance/cashier capture screens must not 403 on the WHT dropdown.
    // =========================================================================

    @Test
    void apClerk_withOnlyApBillEnter_canListWhtTypes() throws Exception {
        // AP clerk holds AP.BILL.ENTER but NOT WHT.VIEW. The WHT-type dropdown on the
        // bill-entry capture form must return 200 (member read floor).
        mockMvc.perform(get("/api/v1/wht/types")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + apClerkToken))
                .andExpect(status().isOk());
    }

    @Test
    void nonMember_noWhtViewGrant_listWhtTypes_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/wht/types")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // =========================================================================
    // GET /api/v1/purchase-orders — dual-permission gate (PURCHASE.ORDER.VIEW OR AP.BILL.ENTER)
    // ISSUE-002: the supplier-bill three-way-match picker needs PO list access for AP clerks
    // who don't hold the full purchasing-module read but do hold AP.BILL.ENTER.
    // =========================================================================

    @Test
    void apClerk_withOnlyApBillEnter_canListPurchaseOrders() throws Exception {
        // AP clerk holds AP.BILL.ENTER (not PURCHASE.ORDER.VIEW). The dual-permission gate
        // "@perm.has('PURCHASE.ORDER.VIEW') or @perm.has('AP.BILL.ENTER')" must admit them.
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + apClerkToken))
                .andExpect(status().isOk());
    }

    @Test
    void nonMember_noPurchasingGrant_listPurchaseOrders_returns403() throws Exception {
        // Zero grants → neither PURCHASE.ORDER.VIEW nor AP.BILL.ENTER → 403.
        mockMvc.perform(get("/api/v1/purchase-orders")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // =========================================================================
    // F22 — Customer/supplier party-picker gate is INTENTIONALLY strict (option b).
    //
    // The security decision (2026-06-28) is: CUSTOMER.VIEW / SUPPLIER.VIEW gates are
    // NOT relaxed to a member read-floor because party master data (TIN, VRN, credit
    // limit, mobile-money) is more sensitive than ambient reference lists. The fix is
    // role composition (grant CUSTOMER.VIEW to cash/AR roles; SUPPLIER.VIEW to AP
    // roles), not a gate change. These tests pin that decision so a future "helpfully"
    // relaxed gate immediately turns red.
    // =========================================================================

    /**
     * A user holding only {@code AP.BILL.ENTER} (no {@code CUSTOMER.VIEW}) must get
     * 403 on {@code GET /customers}.  The gate is correct — the role is mis-specified.
     * Pin this so a future {@code hasOrMember} relaxation fails the build.
     */
    @Test
    void apClerk_withoutCustomerView_listCustomers_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + apClerkToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    /**
     * A user holding only {@code AP.BILL.ENTER} (no {@code SUPPLIER.VIEW}) must get
     * 403 on {@code GET /suppliers}.  Same rationale as the customer gate above.
     */
    @Test
    void apClerk_withoutSupplierView_listSuppliers_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + apClerkToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    /**
     * A user holding {@code CUSTOMER.VIEW} (and nothing else) must be admitted to
     * {@code GET /customers}.  Confirms the gate is live and that a legitimate
     * role-grant works — the fix is additive grants, not a gate change.
     */
    @Test
    void customerViewHolder_canListCustomers() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + customerViewToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * A user holding {@code SUPPLIER.VIEW} (and nothing else) must be admitted to
     * {@code GET /suppliers}.
     */
    @Test
    void supplierViewHolder_canListSuppliers() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + supplierViewToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray());
    }

    /**
     * A zero-grant (non-member) user must be 403'd on {@code GET /customers}.
     * Fail-closed baseline matching the branch/product/WHT non-member tests above.
     */
    @Test
    void nonMember_noCustomerViewGrant_listCustomers_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/customers")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    /**
     * A zero-grant (non-member) user must be 403'd on {@code GET /suppliers}.
     */
    @Test
    void nonMember_noSupplierViewGrant_listSuppliers_returns403() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + nonMemberToken))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.errors").isArray());
    }

    // =========================================================================
    // Baseline: root is always admitted
    // =========================================================================

    @Test
    void root_canListBranches() throws Exception {
        mockMvc.perform(get("/api/v1/branches")
                        .param("companyUid", company.getUid())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk());
    }

    @Test
    void root_canListProducts() throws Exception {
        mockMvc.perform(get("/api/v1/products")
                        .param("companyId", company.getId().toString())
                        .header("Authorization", "Bearer " + rootToken))
                .andExpect(status().isOk());
    }

    // =========================================================================
    // Private helpers — mirror ArHttpIT / ApHttpIT pattern
    // =========================================================================

    private Role buildRole(String code, String permissionCode) {
        Permission perm = permissions.findByCode(permissionCode)
                .orElseThrow(() -> new IllegalStateException(
                        permissionCode + " must be seeded by R__seed_permissions.sql"));
        Role role = new Role(code, code);
        role.setPermissions(Set.of(perm));
        return roles.save(role);
    }

    private void grantRoleAsRoot(AppUser user, Role role) {
        RequestContext.set(new RequestContext.Principal(
                rootUser.getId(), rootUser.getUsername(), true,
                company.getId(), branch.getId(), null));
        try {
            testData.seedMembership(user.getUid(), company.getUid());
            userRoleService.grant(new GrantRoleRequest(
                    user.getUid(), role.getUid(), company.getUid(), null));
            permissionResolver.invalidate();
        } finally {
            RequestContext.clear();
        }
    }
}
