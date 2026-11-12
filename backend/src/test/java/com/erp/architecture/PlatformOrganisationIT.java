package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.UserRole;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.repository.UserRoleRepository;
import com.erp.platform.bootstrap.TenantProvisioningService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.PlatformOrganisationRegistry;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * The platform organisation (owner, 2026-11-07). A server whose root lives inside a customer's
 * organisation gets a separate Platform organisation with one click, the operator is moved there,
 * the customer is untouched, and the platform organisation then refuses business writes and its own
 * suspension, export and entry.
 */
@AutoConfigureMockMvc
class PlatformOrganisationIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IamTestData testData;
    @Autowired private TenantProvisioningService provisioning;
    @Autowired private PermissionResolver permissionResolver;
    @Autowired private JwtService jwtService;
    @Autowired private AppUserRepository users;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private RoleRepository roles;
    @Autowired private UserRoleRepository userRoles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private PlatformOrganisationRegistry registry;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;

    private TenantProvisioningService.ProvisionedTenant cust;   // root lives HERE, like a customer box
    private TenantProvisioningService.ProvisionedTenant other;  // an ordinary second tenant
    private String custName;
    private AppUser root;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        jdbc.update("DELETE FROM organisations WHERE alias = 'platform'");
        registry.reload();
        RequestContext.clear();
        String tag = UUID.randomUUID().toString().substring(0, 8);
        custName = "Customer " + tag;
        cust = provisioning.provision(request(custName, "C" + tag, "rootadmin" + tag, false, true));
        other = provisioning.provision(request("Other " + tag, "O" + tag, "orgadmin", true, false));
        root = users.findById(cust.adminUserId()).orElseThrow();
        assertThat(root.isRoot()).isTrue();
        permissionResolver.invalidate();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        testData.clearAll();
        jdbc.update("DELETE FROM organisations WHERE alias = 'platform'");
        registry.reload();
    }

    private static TenantProvisioningService.NewTenantRequest request(String name, String code,
                                                                     String admin, boolean compose,
                                                                     boolean root) {
        return new TenantProvisioningService.NewTenantRequest(name, "Africa/Dar_es_Salaam",
                code, name + " Co", null, null, null, "B" + code, name + " Branch", admin,
                "OrgPass12345", name + " Admin", "TZS", "TZS", List.of("TZS"), null, null, null,
                "Walk-in " + name, null, compose, root);
    }

    /** A non-root ORG_ADMIN of the customer: the precondition the button insists on. */
    private void giveCustomerItsOwnAdministrator() {
        AppUser staff = new AppUser("manager" + UUID.randomUUID().toString().substring(0, 6),
                passwordEncoder.encode("OrgPass12345"), "Customer Manager");
        staff.setOrganisationId(cust.organisationId());
        staff = users.save(staff);
        userRoles.save(new UserRole(staff.getId(), roles.findByCode("ORG_ADMIN").orElseThrow(),
                cust.companyId(), null, root.getId()));
    }

    private String rootTokenAtHome() {
        return jwtService.issueAccessToken(users.findById(root.getId()).orElseThrow(),
                cust.companyId(), cust.branchId()).value();
    }

    private MvcResult call(MockHttpServletRequestBuilder rb, String token, String branchUid)
            throws Exception {
        rb.header("Authorization", "Bearer " + token);
        if (branchUid != null) {
            rb.header("X-Branch-Uid", branchUid);
        }
        return mockMvc.perform(rb).andReturn();
    }

    private MvcResult createPlatform(String token, String confirm) throws Exception {
        return call(MockMvcRequestBuilders.post("/api/v1/organisations/platform")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("confirmName", confirm))), token, null);
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private long count(String sql, Object... args) {
        Long n = jdbc.queryForObject(sql, Long.class, args);
        return n == null ? 0 : n;
    }

    @Test
    @DisplayName("refused while the customer has no administrator of its own")
    void refusedWithoutCustomerAdministrator() throws Exception {
        MvcResult r = createPlatform(rootTokenAtHome(), custName);
        assertThat(r.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(r)).contains("Organisation administrator role");
        assertThat(organisations.findByAlias("platform")).isEmpty();
    }

    @Test
    @DisplayName("refused unless the customer's exact name is typed")
    void refusedWithWrongName() throws Exception {
        giveCustomerItsOwnAdministrator();
        assertThat(createPlatform(rootTokenAtHome(), custName.toLowerCase()).getResponse()
                .getStatus()).isEqualTo(400);
        assertThat(organisations.findByAlias("platform")).isEmpty();
    }

    @Test
    @DisplayName("one click: Platform created, operator moved, customer untouched, audited, once only")
    void createsPlatformAndMovesTheOperator() throws Exception {
        giveCustomerItsOwnAdministrator();
        String[] businessTables = {"customers", "chart_of_accounts", "price_lists", "tax_rates",
                "stock_locations", "branches", "pos_tills"};
        Map<String, Long> before = new java.util.TreeMap<>();
        for (String t : businessTables) {
            before.put(t, count("SELECT count(*) FROM " + t + " WHERE company_id = ?", cust.companyId()));
        }
        jdbc.update("INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at) VALUES "
                + "(nextval('refresh_tokens_id_seq'), ?, ?, now() + interval '7 days')",
                root.getId(), "RT-" + UUID.randomUUID());

        MvcResult r = createPlatform(rootTokenAtHome(), custName);
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(201);

        var platform = organisations.findByAlias("platform").orElseThrow();
        assertThat(platform.getName()).isEqualTo("Platform");
        AppUser moved = users.findById(root.getId()).orElseThrow();
        assertThat(moved.getOrganisationId()).isEqualTo(platform.getId());
        assertThat(moved.isRoot()).isTrue();
        Long platformCompany = companies.findByOrganisationIdOrderByName(platform.getId()).get(0).getId();
        assertThat(count("SELECT count(*) FROM user_branch ub JOIN branches b ON b.id = ub.branch_id "
                + "WHERE ub.user_id = ? AND ub.is_default AND ub.revoked_at IS NULL AND b.company_id = ?",
                root.getId(), platformCompany)).as("exactly one default branch, in Platform").isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_branch WHERE user_id = ? AND is_default",
                root.getId())).isEqualTo(1);
        assertThat(count("SELECT count(*) FROM user_branch ub JOIN branches b ON b.id = ub.branch_id "
                + "WHERE ub.user_id = ? AND b.company_id = ? AND ub.revoked_at IS NULL",
                root.getId(), cust.companyId())).as("old assignments revoked, not deleted").isZero();
        assertThat(count("SELECT count(*) FROM refresh_tokens WHERE user_id = ? AND revoked_at IS NULL",
                root.getId())).as("signed out everywhere").isZero();

        for (String t : businessTables) {
            assertThat(count("SELECT count(*) FROM " + t + " WHERE company_id = ?", cust.companyId()))
                    .as("customer's %s untouched", t).isEqualTo(before.get(t));
        }
        assertThat(count("SELECT count(*) FROM audit_logs WHERE action = 'ORG.PLATFORM.CREATE' "
                + "AND detail->>'previousOrganisationId' = ?", String.valueOf(cust.organisationId())))
                .isEqualTo(1);
        assertThat(registry.isPlatform(platform.getId())).as("guard cache refreshed").isTrue();

        MvcResult again = createPlatform(rootTokenAtHome(), custName);
        assertThat(again.getResponse().getStatus()).isIn(404, 409);
    }

    @Test
    @DisplayName("after the move: business writes refused at home, allowed inside a tenant; Platform protected")
    void platformRefusesBusinessAndProtectsItself() throws Exception {
        giveCustomerItsOwnAdministrator();
        assertThat(createPlatform(rootTokenAtHome(), custName).getResponse().getStatus()).isEqualTo(201);
        var platform = organisations.findByAlias("platform").orElseThrow();
        var pCompany = companies.findByOrganisationIdOrderByName(platform.getId()).get(0);
        var pBranch = branches.findByCompanyIdAndIsDefaultTrue(pCompany.getId()).orElseThrow();
        String token = jwtService.issueAccessToken(users.findById(root.getId()).orElseThrow(),
                pCompany.getId(), pBranch.getId()).value();
        String custBranchUid = branches.findById(cust.branchId()).orElseThrow().getUid();
        String custOrgUid = organisations.findById(cust.organisationId()).orElseThrow().getUid();

        // The customer is now an ordinary tenant in the list; Platform is "current".
        JsonNode tenants = json.readTree(body(call(MockMvcRequestBuilders.get(
                "/api/v1/organisations/tenants"), token, null))).path("data");
        assertThat(tenants.size()).isEqualTo(3);

        Map<String, Object> customer = Map.of("companyId", pCompany.getId(), "partyType", "BUSINESS",
                "displayName", "Not allowed here", "tin", "111-222-333", "customerKind", "CREDIT_ACCOUNT");
        MvcResult home = call(MockMvcRequestBuilders.post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(customer)),
                token, null);
        assertThat(home.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(home)).contains("holds no business data");

        Map<String, Object> inside = new java.util.HashMap<>(customer);
        inside.put("companyId", cust.companyId());
        inside.put("displayName", "Made inside the customer");
        MvcResult entered = call(MockMvcRequestBuilders.post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(inside)),
                token, custBranchUid);
        assertThat(entered.getResponse().getStatus()).as(body(entered)).isIn(200, 201);

        // Platform cannot be suspended - not even from inside a tenant, where it is "foreign".
        MvcResult suspend = call(MockMvcRequestBuilders.post("/api/v1/organisations/uid/"
                + platform.getUid() + "/suspend"), token, custBranchUid);
        assertThat(suspend.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(suspend)).contains("cannot be suspended");
        assertThat(call(MockMvcRequestBuilders.post("/api/v1/organisations/uid/" + platform.getUid()
                + "/export"), token, custBranchUid).getResponse().getStatus()).isEqualTo(409);
        assertThat(call(MockMvcRequestBuilders.get("/api/v1/organisations/uid/" + platform.getUid()
                + "/entry"), token, null).getResponse().getStatus()).isEqualTo(409);
        // ...while the customer can be entered like any tenant.
        assertThat(call(MockMvcRequestBuilders.get("/api/v1/organisations/uid/" + custOrgUid
                + "/entry"), token, null).getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("sweep: EVERY business write endpoint is refused in Platform before it runs")
    void everyBusinessWriteIsRefusedInPlatform() throws Exception {
        giveCustomerItsOwnAdministrator();
        assertThat(createPlatform(rootTokenAtHome(), custName).getResponse().getStatus()).isEqualTo(201);
        var platform = organisations.findByAlias("platform").orElseThrow();
        var pCompany = companies.findByOrganisationIdOrderByName(platform.getId()).get(0);
        var pBranch = branches.findByCompanyIdAndIsDefaultTrue(pCompany.getId()).orElseThrow();
        String token = jwtService.issueAccessToken(users.findById(root.getId()).orElseThrow(),
                pCompany.getId(), pBranch.getId()).value();

        List<String> leaks = new ArrayList<>();
        int checked = 0;
        for (var e : mappings.getHandlerMethods().entrySet()) {
            var info = e.getKey();
            var methods = info.getMethodsCondition().getMethods();
            if (methods.isEmpty() || methods.contains(RequestMethod.GET)) {
                continue;
            }
            var patterns = info.getPatternValues();
            if (patterns.isEmpty()) {
                continue;
            }
            String pattern = patterns.iterator().next();
            if (!pattern.startsWith("/api/") || isPlatformAdministration(pattern)) {
                continue;
            }
            String url = pattern.replaceAll("\\{[^}]+}", "x");
            RequestMethod m = methods.iterator().next();
            MockHttpServletRequestBuilder rb = MockMvcRequestBuilders.request(
                    org.springframework.http.HttpMethod.valueOf(m.name()), url);
            var consumes = info.getConsumesCondition().getConsumableMediaTypes();
            rb.contentType(consumes.isEmpty() ? MediaType.APPLICATION_JSON : consumes.iterator().next());
            rb.content("{}");
            for (var p : info.getParamsCondition().getExpressions()) {
                if (!p.isNegated()) {
                    rb.param(p.getName(), p.getValue() == null ? "x" : p.getValue());
                }
            }
            MvcResult r = call(rb, token, null);
            checked++;
            String text = body(r);
            if (r.getResponse().getStatus() != 409 || !text.contains("holds no business data")) {
                leaks.add(m + " " + pattern + " -> " + r.getResponse().getStatus());
            }
        }
        System.out.println("[platform-sweep] business write endpoints checked: " + checked);
        assertThat(checked).as("the sweep must actually reach the business endpoints")
                .isGreaterThan(200);
        assertThat(leaks).as("business writes that reached a handler in Platform").isEmpty();
    }

    private static boolean isPlatformAdministration(String pattern) {
        for (String p : List.of("/api/v1/auth", "/api/v1/organisations", "/api/v1/users",
                "/api/v1/user-roles", "/api/v1/user-branches", "/api/v1/user-companies",
                "/api/v1/roles")) {
            if (pattern.equals(p) || pattern.startsWith(p + "/")) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("a customer named like 'platform' is refused through the real API")
    void lookalikeCustomerNameIsRefused() throws Exception {
        for (String name : List.of("Platform", "Pllatform", "P1atform Ltd")) {
            MvcResult r = call(MockMvcRequestBuilders.post("/api/v1/organisations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("organisationName", name,
                            "companyCode", "LK", "companyName", "Lookalike", "branchCode", "LK1",
                            "branchName", "Lookalike HQ", "adminUsername", "admin",
                            "adminPassword", "OrgPass12345"))), rootTokenAtHome(), null);
            assertThat(r.getResponse().getStatus()).as(name).isEqualTo(409);
            assertThat(body(r)).contains("too close to");
        }
    }
}
