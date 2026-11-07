package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.bootstrap.TenantProvisioningService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/**
 * Owner decision 2026-11-07 (reverses D-2 for root): the platform operator ({@code is_root}) may
 * switch INTO any tenant, read and write, and every request it makes there is audited. A tenant's
 * administrator (never root) stays confined to its own tenant. Exercised through the real filter
 * chain with real tokens.
 */
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class RootEntersTenantIT extends PostgresIntegrationTest {

    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper json;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IamTestData testData;
    @Autowired private TenantProvisioningService provisioning;
    @Autowired private PermissionResolver permissionResolver;
    @Autowired private JwtService jwtService;
    @Autowired private AppUserRepository users;
    @Autowired private OrganisationRepository organisations;
    @Autowired private BranchRepository branches;

    private record T(Long orgId, String orgUid, Long companyId, Long branchId, String branchUid,
                     String token) {
    }

    private T home;    // the platform operator's own organisation; its admin IS root
    private T beta;    // a customer; its admin is NOT root

    @BeforeEach
    void setUp() {
        testData.clearAll();
        RequestContext.clear();
        home = provision("H", true);
        beta = provision("B", false);
        permissionResolver.invalidate();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
        testData.clearAll();
    }

    private T provision(String label, boolean root) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        var p = provisioning.provision(new TenantProvisioningService.NewTenantRequest(
                "Enter " + label + " " + tag, "Africa/Dar_es_Salaam", "EN" + label + tag,
                "Enter " + label + " Co", null, null, null, "EB" + label + tag,
                "Enter " + label + " Branch", root ? "rootadmin" + tag : "orgadmin", "OrgPass12345",
                "Enter " + label + " Admin", "TZS", "TZS", List.of("TZS"), null, null, null,
                "Walk-in " + label, null, !root, root));
        AppUser admin = users.findById(p.adminUserId()).orElseThrow();
        assertThat(admin.isRoot()).isEqualTo(root);
        return new T(p.organisationId(), organisations.findById(p.organisationId()).orElseThrow()
                .getUid(), p.companyId(), p.branchId(),
                branches.findById(p.branchId()).orElseThrow().getUid(),
                jwtService.issueAccessToken(admin, p.companyId(), p.branchId()).value());
    }

    private MvcResult call(MockHttpServletRequestBuilder rb, String token, String branchUid)
            throws Exception {
        rb.header("Authorization", "Bearer " + token);
        if (branchUid != null) {
            rb.header("X-Branch-Uid", branchUid);
        }
        return mockMvc.perform(rb).andReturn();
    }

    private JsonNode data(MvcResult r) throws Exception {
        return json.readTree(r.getResponse().getContentAsString(StandardCharsets.UTF_8)).path("data");
    }

    @Test
    @DisplayName("root without a header stays home; with a tenant's branch it IS that tenant")
    void rootRunsAsTheTenantItEnters() throws Exception {
        MvcResult atHome = call(MockMvcRequestBuilders.get("/api/v1/organisations/current"),
                home.token(), null);
        assertThat(data(atHome).path("uid").asText()).isEqualTo(home.orgUid());

        MvcResult inside = call(MockMvcRequestBuilders.get("/api/v1/organisations/current"),
                home.token(), beta.branchUid());
        assertThat(inside.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(inside).path("uid").asText()).as("organisation follows the entered branch")
                .isEqualTo(beta.orgUid());

        MvcResult customers = call(MockMvcRequestBuilders.get("/api/v1/customers")
                .param("companyId", String.valueOf(beta.companyId())), home.token(), beta.branchUid());
        assertThat(customers.getResponse().getStatus()).isEqualTo(200);
        assertThat(customers.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .as("reads the tenant's own data").contains("Walk-in B");
    }

    @Test
    @DisplayName("root writes inside the tenant; the row is the tenant's and the visit is audited")
    void rootWritesInsideTheTenantAndIsAudited() throws Exception {
        MvcResult created = call(MockMvcRequestBuilders.post("/api/v1/customers")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("companyId", beta.companyId(),
                                "partyType", "BUSINESS", "displayName", "Made by the operator", "tin", "111-222-333",
                                "customerKind", "CREDIT_ACCOUNT"))),
                home.token(), beta.branchUid());
        assertThat(created.getResponse().getStatus()).as(created.getResponse()
                .getContentAsString(StandardCharsets.UTF_8)).isIn(200, 201);
        assertThat(jdbc.queryForObject("SELECT company_id FROM customers WHERE display_name = ?",
                Long.class, "Made by the operator")).isEqualTo(beta.companyId());

        Integer switches = jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'BRANCH.SWITCH'
                  AND detail->>'crossTenant' = 'true'
                  AND detail->>'fromOrganisationId' = ? AND detail->>'toOrganisationId' = ?
                """, Integer.class, String.valueOf(home.orgId()), String.valueOf(beta.orgId()));
        assertThat(switches).as("every request inside another tenant leaves a BRANCH.SWITCH row")
                .isGreaterThanOrEqualTo(1);
        Integer stamped = jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE action = 'BRANCH.SWITCH'
                  AND organisation_id = ? AND detail->>'crossTenant' = 'true'
                """, Integer.class, beta.orgId());
        assertThat(stamped).as("and it is on the TENANT's own audit trail").isGreaterThanOrEqualTo(1);
    }

    @Test
    @DisplayName("a tenant's administrator (not root) is still refused another tenant's branch")
    void nonRootIsStillConfined() throws Exception {
        MvcResult r = call(MockMvcRequestBuilders.get("/api/v1/organisations/current"),
                beta.token(), home.branchUid());
        assertThat(r.getResponse().getStatus()).isEqualTo(403);
        // The user-facing text is the generic permission message (error-message hygiene); the
        // status is what proves the refusal.
        assertThat(r.getResponse().getContentAsString(StandardCharsets.UTF_8))
                .doesNotContain(home.orgUid());
    }

    @Test
    @DisplayName("entry endpoint: the operator gets the tenant's default branch; a tenant admin gets 404")
    void entryPointIsPlatformOnly() throws Exception {
        MvcResult op = call(MockMvcRequestBuilders.get("/api/v1/organisations/uid/" + beta.orgUid()
                + "/entry"), home.token(), null);
        assertThat(op.getResponse().getStatus()).isEqualTo(200);
        assertThat(data(op).path("branchUid").asText()).isEqualTo(beta.branchUid());

        MvcResult tenant = call(MockMvcRequestBuilders.get("/api/v1/organisations/uid/" + home.orgUid()
                + "/entry"), beta.token(), null);
        assertThat(tenant.getResponse().getStatus()).isIn(403, 404);
    }
}
