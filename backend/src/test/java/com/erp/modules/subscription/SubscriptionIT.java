package com.erp.modules.subscription;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.bootstrap.TenantProvisioningService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * ADR-0069 end to end against Postgres: V115 applies and maps, the operator assigns a plan, the
 * tenant's permissions, caps and ladder follow it, and a payment restores access.
 */
@AutoConfigureMockMvc
@DisplayName("Tenant subscription plans (ADR-0069)")
class SubscriptionIT extends PostgresIntegrationTest {

    private static final LocalDate TODAY = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IamTestData testData;
    @Autowired private TenantProvisioningService provisioning;
    @Autowired private JwtService jwtService;
    @Autowired private AppUserRepository users;
    @Autowired private OrganisationRepository organisations;
    @Autowired private BranchRepository branches;
    @Autowired private CompanyRepository companies;

    private record T(Long orgId, String orgUid, Long companyId, String companyUid,
                     String branchUid, String token) {
    }

    private T operator;   // root, in its own organisation
    private T tenant;     // a customer; its admin holds ORG_ADMIN, not root

    @BeforeEach
    void setUp() {
        testData.clearAll();
        RequestContext.clear();
        operator = provision("O", true);
        tenant = provision("C", false);
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        testData.clearAll();
    }

    @Test
    @DisplayName("without a plan a tenant is unrestricted: no summary, every granted code")
    void noPlanIsUnrestricted() throws Exception {
        JsonNode me = data(call(MockMvcRequestBuilders.get("/api/v1/auth/me"), tenant.token()));
        assertThat(me.path("subscription").isNull()).isTrue();
        assertThat(codes(me)).contains("GL.VIEW", "POS.SALE.CREATE");

        JsonNode sub = data(call(MockMvcRequestBuilders.get(subscriptionUrl()), operator.token()));
        assertThat(sub.path("assigned").asBoolean()).isFalse();
        assertThat(sub.path("stage").asText()).isEqualTo("UNRESTRICTED");
    }

    @Test
    @DisplayName("on Basic the tenant loses the ledger, keeps the POS, and sees its plan")
    void basicFiltersPermissions() throws Exception {
        assign(terms("BASIC", null, null));

        JsonNode me = data(call(MockMvcRequestBuilders.get("/api/v1/auth/me"), tenant.token()));
        assertThat(codes(me)).contains("POS.SALE.CREATE", "PURCHASE.RECEIVE.DIRECT")
                .doesNotContain("GL.VIEW", "AR.VIEW", "RESTAURANT.ORDER.MANAGE", "HR.PAYROLL.RUN");
        assertThat(me.path("subscription").path("planCode").asText()).isEqualTo("BASIC");
        assertThat(me.path("subscription").path("stage").asText()).isEqualTo("ACTIVE");

        MvcResult accounts = call(MockMvcRequestBuilders.get("/api/v1/gl/periods/fiscal-years/uid/01ARZ3NDEKTSV4RRFFQ69G5FAV"), tenant.token());
        assertThat(accounts.getResponse().getStatus()).as("a Premium screen").isEqualTo(403);
    }

    @Test
    @DisplayName("Basic allows one branch: the second is refused with the plan's name")
    void branchCapIsEnforced() throws Exception {
        assign(terms("BASIC", null, null));
        MvcResult second = call(MockMvcRequestBuilders.post("/api/v1/branches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("companyUid", tenant.companyUid(),
                        "code", "B2", "name", "Second shop", "makeDefault", false))),
                tenant.token());
        assertThat(second.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(second)).contains("Basic plan allows 1 branch");

        // The operator raises the cap for this tenant only; the same request then succeeds.
        Map<String, Object> raised = terms("BASIC", null, null);
        raised.put("maxBranchesOverride", 2);
        assign(raised);
        MvcResult retried = call(MockMvcRequestBuilders.post("/api/v1/branches")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("companyUid", tenant.companyUid(),
                        "code", "B2", "name", "Second shop", "makeDefault", false))),
                tenant.token());
        assertThat(retried.getResponse().getStatus()).as(body(retried)).isIn(200, 201);

        JsonNode sub = data(call(MockMvcRequestBuilders.get(subscriptionUrl()), operator.token()));
        JsonNode branchCap = null;
        for (JsonNode c : sub.path("caps")) {
            if ("BRANCHES".equals(c.path("resource").asText())) {
                branchCap = c;
            }
        }
        assertThat(branchCap).isNotNull();
        assertThat(branchCap.path("planLimit").asInt()).isEqualTo(1);
        assertThat(branchCap.path("effectiveLimit").asInt()).isEqualTo(2);
        assertThat(branchCap.path("inUse").asLong()).isEqualTo(2);
    }

    @Test
    @DisplayName("overdue past grace: reads work, writes are refused; a payment restores access")
    void readOnlyThenPaymentRestores() throws Exception {
        assign(terms("ENTERPRISE", null, TODAY.minusDays(10)));   // 7 grace, then read-only

        JsonNode me = data(call(MockMvcRequestBuilders.get("/api/v1/auth/me"), tenant.token()));
        assertThat(me.path("subscription").path("stage").asText()).isEqualTo("READ_ONLY");

        MvcResult read = call(MockMvcRequestBuilders.get("/api/v1/customers")
                .param("companyId", String.valueOf(tenant.companyId())), tenant.token());
        assertThat(read.getResponse().getStatus()).isEqualTo(200);

        MvcResult write = createCustomer("Blocked Ltd");
        assertThat(write.getResponse().getStatus()).isEqualTo(409);
        assertThat(body(write)).contains("read-only");

        MvcResult paid = call(MockMvcRequestBuilders.post(subscriptionUrl() + "/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("amount", 150000, "currencyCode", "TZS",
                        "paidOn", TODAY.toString(), "paidUntil", TODAY.plusMonths(1).toString(),
                        "reference", "MPESA-123"))), operator.token());
        assertThat(paid.getResponse().getStatus()).as(body(paid)).isEqualTo(200);
        assertThat(data(paid).path("stage").asText()).isEqualTo("ACTIVE");
        assertThat(data(paid).path("payments").get(0).path("reference").asText())
                .isEqualTo("MPESA-123");

        assertThat(createCustomer("Paid Up Ltd").getResponse().getStatus()).isIn(200, 201);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = ?",
                Long.class, "ORG.SUBSCRIPTION.PAYMENT")).isEqualTo(1L);
    }

    @Test
    @DisplayName("suspended: every request and a fresh sign-in are refused")
    void suspendedBlocksEverything() throws Exception {
        assign(terms("BASIC", null, TODAY.minusDays(30)));
        MvcResult read = call(MockMvcRequestBuilders.get("/api/v1/customers")
                .param("companyId", String.valueOf(tenant.companyId())), tenant.token());
        assertThat(read.getResponse().getStatus()).isEqualTo(403);
        assertThat(body(read)).contains("suspended");

        AppUser admin = users.findAll().stream()
                .filter(u -> tenant.orgId().equals(u.getOrganisationId())).findFirst().orElseThrow();
        MvcResult login = mockMvc.perform(MockMvcRequestBuilders.post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("username", admin.getUsername(),
                        "password", "OrgPass12345")))).andReturn();
        assertThat(login.getResponse().getStatus()).isEqualTo(401);
        assertThat(body(login)).contains("subscription has lapsed");
    }

    @Test
    @DisplayName("the OrbixHQ app is refused without the add-on and allowed with it")
    void orbixHqAddOn() throws Exception {
        assign(terms("BASIC", null, null));
        MvcResult fromApp = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + tenant.token())
                .header("X-Client-App", "orbixhq")).andReturn();
        assertThat(fromApp.getResponse().getStatus()).isEqualTo(403);

        Map<String, Object> withApp = terms("BASIC", null, null);
        withApp.put("addOnOrbixHq", true);
        assign(withApp);
        MvcResult allowed = mockMvc.perform(MockMvcRequestBuilders.get("/api/v1/auth/me")
                .header("Authorization", "Bearer " + tenant.token())
                .header("X-Client-App", "orbixhq")).andReturn();
        assertThat(allowed.getResponse().getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("a tenant cannot read or change its own subscription")
    void tenantCannotManageItsSubscription() throws Exception {
        assertThat(call(MockMvcRequestBuilders.get(subscriptionUrl()), tenant.token())
                .getResponse().getStatus()).isIn(403, 404);
        MvcResult put = call(MockMvcRequestBuilders.put(subscriptionUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(terms("ENTERPRISE", null, null))), tenant.token());
        assertThat(put.getResponse().getStatus()).isIn(403, 404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organisation_subscriptions",
                Long.class)).isZero();
    }

    // -------------------------------------------------------------------------

    private void assign(Map<String, Object> terms) throws Exception {
        MvcResult r = call(MockMvcRequestBuilders.put(subscriptionUrl())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(terms)), operator.token());
        assertThat(r.getResponse().getStatus()).as(body(r)).isEqualTo(200);
    }

    private static Map<String, Object> terms(String plan, LocalDate trialEndsOn,
                                             LocalDate paidUntil) {
        Map<String, Object> m = new HashMap<>();
        m.put("planCode", plan);
        m.put("addOnRestaurant", false);
        m.put("addOnOrbixHq", false);
        m.put("billingCycle", "MONTHLY");
        m.put("trialEndsOn", trialEndsOn == null ? null : trialEndsOn.toString());
        m.put("paidUntil", paidUntil == null ? null : paidUntil.toString());
        m.put("graceDays", 7);
        m.put("readOnlyDays", 7);
        return m;
    }

    private MvcResult createCustomer(String name) throws Exception {
        return call(MockMvcRequestBuilders.post("/api/v1/customers")
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(Map.of("companyId", tenant.companyId(),
                        "partyType", "BUSINESS", "displayName", name, "tin", "111-222-333",
                        "customerKind", "CREDIT_ACCOUNT"))), tenant.token());
    }

    private String subscriptionUrl() {
        return "/api/v1/organisations/uid/" + tenant.orgUid() + "/subscription";
    }

    private T provision(String label, boolean root) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        var p = provisioning.provision(new TenantProvisioningService.NewTenantRequest(
                "Plan " + label + " " + tag, "Africa/Dar_es_Salaam", "PL" + label + tag,
                "Plan " + label + " Co", null, null, null, "PB" + label + tag,
                "Plan " + label + " Branch", root ? "rootadmin" + tag : "orgadmin", "OrgPass12345",
                "Plan " + label + " Admin", "TZS", "TZS", List.of("TZS"), null, null, null,
                "Walk-in " + label, null, !root, root));
        AppUser admin = users.findById(p.adminUserId()).orElseThrow();
        return new T(p.organisationId(),
                organisations.findById(p.organisationId()).orElseThrow().getUid(),
                p.companyId(), companies.findById(p.companyId()).orElseThrow().getUid(),
                branches.findById(p.branchId()).orElseThrow().getUid(),
                jwtService.issueAccessToken(admin, p.companyId(), p.branchId()).value());
    }

    private MvcResult call(MockHttpServletRequestBuilder rb, String token) throws Exception {
        return mockMvc.perform(rb.header("Authorization", "Bearer " + token)).andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return json.readTree(body(r)).path("data");
    }

    private static String body(MvcResult r) throws Exception {
        return r.getResponse().getContentAsString(StandardCharsets.UTF_8);
    }

    private static List<String> codes(JsonNode me) {
        List<String> out = new java.util.ArrayList<>();
        me.path("permissions").forEach(n -> out.add(n.asText()));
        return out;
    }
}
