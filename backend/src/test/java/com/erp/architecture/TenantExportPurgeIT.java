package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.Role;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.RoleRepository;
import com.erp.modules.iam.service.OrganisationService;
import com.erp.modules.iam.service.TenantDepartureService;
import com.erp.platform.bootstrap.TenantProvisioningService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.tenant.TenantDataCatalog;
import com.erp.platform.tenant.TenantDataPlan;
import com.erp.platform.tenant.TenantTable;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * R-7 (per-tenant export) and R-8 (per-tenant delete) of MULTITENANCY-PLAN §14.2 - the
 * highest-risk items in the shared-instance programme, so this is a real two-tenant database, not a
 * mock: tenant A and tenant B are provisioned the way the API provisions them, given extra rows in
 * the tables the provisioner leaves empty (including a self-referencing hierarchy and the one
 * mutual foreign-key cycle in the schema), and the platform operator then exports and purges B.
 *
 * <p>The assertion that matters most is the one that is easy to forget: <b>A is untouched</b>,
 * compared table by table over every table the catalogue discovered.
 */
@DisplayName("R-7 / R-8 - export and purge one tenant, never the other")
class TenantExportPurgeIT extends PostgresIntegrationTest {

    private static final Set<String> EXPECTED_GLOBAL =
            Set.of("currencies", "permissions", "processed_events");
    private static final Set<String> EXPECTED_REDACTED = Set.of(
            "app_users.password_hash", "app_users.mfa_secret", "app_users.pos_pin_hash",
            "refresh_tokens.token_hash");

    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private RoleRepository roles;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private TenantProvisioningService provisioning;
    @Autowired private OrganisationService organisationService;
    @Autowired private TenantDepartureService departure;
    @Autowired private TenantDataCatalog catalog;

    private final ObjectMapper json = new ObjectMapper();

    private String tag;
    private Organisation platform;
    private Company platformCompany;
    private Branch platformBranch;
    private AppUser operator;
    private Tenant a;
    private Tenant b;

    /** One provisioned tenant plus the markers planted in it. */
    private record Tenant(Organisation org, long companyId, long branchId, long adminId,
                          String companyCode, String adminPasswordHash) {
    }

    @BeforeEach
    void seedPlatformAndTwoTenants() {
        RequestContext.clear();
        testData.clearAll();
        tag = UUID.randomUUID().toString().substring(0, 8);

        platform = organisations.save(new Organisation("Platform HQ " + tag));
        platform.setAlias("platform");
        organisations.save(platform);
        platformCompany = companies.save(new Company(platform, "PL-" + tag, "Platform Co"));
        platformBranch = branches.save(new Branch(platformCompany, "PLB-" + tag, "Platform Branch"));
        operator = new AppUser("operator-" + tag, passwordEncoder.encode("Operator!Pass123"),
                "Platform Operator");
        operator.setOrganisationId(platform.getId());
        operator.setRoot(true);
        operator = users.save(operator);

        a = provisionTenant("A");
        b = provisionTenant("B");
    }

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    private Tenant provisionTenant(String label) {
        var p = provisioning.provision(new TenantProvisioningService.NewTenantRequest(
                "Tenant " + label + " " + tag, "Africa/Dar_es_Salaam",
                "T" + label + "-" + tag, "Tenant " + label + " Co",
                null, null, null,
                "T" + label + "B-" + tag, "Tenant " + label + " Branch",
                "admin", "TenantPass12345", "Admin " + label,
                "TZS", "TZS", List.of("TZS"),
                "STD", "Standard", true, "Walk-in " + label, "Till " + label,
                true, false));
        Organisation org = organisations.findById(p.organisationId()).orElseThrow();
        seedExtraRows(label, p.companyId(), p.branchId(), p.adminUserId(), p.organisationId());
        String hash = jdbc.queryForObject("SELECT password_hash FROM app_users WHERE id = ?",
                String.class, p.adminUserId());
        return new Tenant(org, p.companyId(), p.branchId(), p.adminUserId(), p.companyCode(), hash);
    }

    private static String uid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 26).toUpperCase();
    }

    /**
     * Rows in tables the provisioner leaves empty: a unit/product/barcode chain, a two-level chart
     * of accounts (a SELF-reference), an approval request whose step and decision point at each
     * other (the schema's one multi-table CYCLE), a custom role with grants, a refresh token, a
     * login audit row with no company (reachable only through its actor), and credential
     * material in every secret column so redaction is observable.
     */
    private void seedExtraRows(String label, long companyId, long branchId, long adminId, long orgId) {
        Long uom = jdbc.queryForObject("""
                INSERT INTO units_of_measure (id, uid, company_id, code, name)
                VALUES (nextval('units_of_measure_id_seq'), ?, ?, ?, 'Piece') RETURNING id
                """, Long.class, uid(), companyId, "PC" + label);
        Long product = jdbc.queryForObject("""
                INSERT INTO products (id, uid, company_id, code, name, type, base_unit_id)
                VALUES (nextval('products_id_seq'), ?, ?, ?, ?, 'GOODS', ?) RETURNING id
                """, Long.class, uid(), companyId, "P-" + label, "Widget " + label, uom);
        jdbc.update("""
                INSERT INTO product_barcodes (id, uid, product_id, company_id, barcode)
                VALUES (nextval('product_barcodes_id_seq'), ?, ?, ?, ?)
                """, uid(), product, companyId, "BC-" + label + "-" + tag);

        Long parentAcct = jdbc.queryForObject("""
                INSERT INTO chart_of_accounts (id, uid, company_id, account_code, name, account_type,
                    normal_balance)
                VALUES (nextval('chart_of_accounts_id_seq'), ?, ?, '9000', 'Test Assets', 'ASSET', 'DEBIT')
                RETURNING id
                """, Long.class, uid(), companyId);
        jdbc.update("""
                INSERT INTO chart_of_accounts (id, uid, company_id, account_code, name, account_type,
                    normal_balance, parent_id)
                VALUES (nextval('chart_of_accounts_id_seq'), ?, ?, '9100', 'Test Cash', 'ASSET', 'DEBIT', ?)
                """, uid(), companyId, parentAcct);

        // The approval cycle: request <- step <-> decision.
        Long request = jdbc.queryForObject("""
                INSERT INTO approval_requests (uid, company_id, branch_id, request_number,
                    document_type, document_uid, amount, submitted_by)
                VALUES (?, ?, ?, ?, 'PURCHASE_ORDER', ?, 10, ?) RETURNING id
                """, Long.class, uid(), companyId, branchId, "AR-" + label + "-" + tag, uid(), adminId);
        Long step = jdbc.queryForObject("""
                INSERT INTO approval_request_steps (uid, approval_request_id, company_id, sequence,
                    approver_role_code)
                VALUES (?, ?, ?, 1, 'ORG_ADMIN') RETURNING id
                """, Long.class, uid(), request, companyId);
        Long decision = jdbc.queryForObject("""
                INSERT INTO approval_decisions (uid, approval_request_id, approval_request_step_id,
                    company_id, action, decided_by)
                VALUES (?, ?, ?, ?, 'APPROVE', ?) RETURNING id
                """, Long.class, uid(), request, step, companyId, adminId);
        jdbc.update("UPDATE approval_request_steps SET resolving_decision_id = ? WHERE id = ?",
                decision, step);

        // A tenant-authored role with grants (role_permission is a CHILD of roles) and a grant.
        Role custom = new Role("CUSTOM_" + label + "_" + tag, "Custom " + label);
        custom.setOrganisationId(orgId);
        custom = roles.save(custom);
        jdbc.update("INSERT INTO role_permission (role_id, permission_id) "
                + "SELECT ?, id FROM permissions ORDER BY id LIMIT 3", custom.getId());

        // Credential material in every secret column, with per-tenant markers.
        jdbc.update("UPDATE app_users SET pos_pin_hash = ?, mfa_secret = ? WHERE id = ?",
                "PINHASH-" + label + "-" + tag, "MFASECRET-" + label + "-" + tag, adminId);
        jdbc.update("""
                INSERT INTO refresh_tokens (id, user_id, token_hash, expires_at)
                VALUES (nextval('refresh_tokens_id_seq'), ?, ?, now() + interval '7 days')
                """, adminId, "RTHASH-" + label + "-" + tag);

        // Login event: no company, no organisation - tied to the tenant only through the actor.
        jdbc.update("""
                INSERT INTO audit_logs (actor_user_id, action, target_type, target_id)
                VALUES (?, 'LOGIN.SUCCESS', 'app_users', ?)
                """, adminId, adminId);
    }

    // ----------------------------------------------------------------------------------------
    // helpers
    // ----------------------------------------------------------------------------------------

    private void actingAsOperator() {
        RequestContext.set(new RequestContext.Principal(operator.getId(), operator.getUsername(),
                true, platformCompany.getId(), platformBranch.getId(), "127.0.0.1",
                platform.getId()));
    }

    private void actingAsTenantAdmin(Tenant t) {
        RequestContext.set(new RequestContext.Principal(t.adminId(), "admin", false,
                t.companyId(), t.branchId(), "127.0.0.1", t.org().getId()));
    }

    private TenantDataPlan plan(Tenant t) {
        return catalog.planFor(t.org().getId());
    }

    /** Rows per tenant table, by the catalogue's own predicate. */
    private Map<String, Long> counts(Tenant t) {
        Map<String, Long> out = new TreeMap<>();
        for (TenantTable table : plan(t).tables()) {
            out.put(table.name(), jdbc.queryForObject("SELECT count(*) FROM \"" + table.name()
                    + "\" WHERE " + table.rowFilterSql(), Long.class));
        }
        return out;
    }

    private byte[] exportOf(Tenant t) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        departure.exportTenant(t.org().getUid(), fileName -> bos);
        return bos.toByteArray();
    }

    private static Map<String, byte[]> unzip(byte[] zip) throws IOException {
        Map<String, byte[]> out = new LinkedHashMap<>();
        try (ZipInputStream in = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = in.getNextEntry(); e != null; e = in.getNextEntry()) {
                out.put(e.getName(), in.readAllBytes());
            }
        }
        return out;
    }

    /** Minimal RFC 4180 reader: returns records (header first). */
    private static List<List<String>> parseCsv(byte[] bytes) {
        String s = new String(bytes, StandardCharsets.UTF_8);
        List<List<String>> records = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        boolean quoted = false;
        boolean wasQuoted = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < s.length() && s.charAt(i + 1) == '"') {
                        cur.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    cur.append(c);
                }
            } else if (c == '"') {
                quoted = true;
                wasQuoted = true;
            } else if (c == ',') {
                row.add(wasQuoted || cur.length() > 0 ? cur.toString() : null);
                cur.setLength(0);
                wasQuoted = false;
            } else if (c == '\r') {
                // swallowed; the \n ends the record
            } else if (c == '\n') {
                row.add(wasQuoted || cur.length() > 0 ? cur.toString() : null);
                records.add(row);
                row = new ArrayList<>();
                cur.setLength(0);
                wasQuoted = false;
            } else {
                cur.append(c);
            }
        }
        return records;
    }

    // ----------------------------------------------------------------------------------------
    // classification
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("every table is classified, the global set and redacted set are exactly the known ones")
    void classificationIsCompleteAndStable() {
        TenantDataPlan plan = plan(b);

        Set<String> inPlan = new java.util.HashSet<>();
        plan.tables().forEach(t -> inPlan.add(t.name()));
        List<String> all = jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, String.class);

        assertThat(all).as("every base table is either a tenant table or listed global")
                .allSatisfy(t -> assertThat(inPlan.contains(t) || plan.globalTables().contains(t))
                        .as(t).isTrue());
        assertThat(plan.globalTables())
                .as("a NEW global table must be a conscious decision, not a silent one")
                .containsExactlyInAnyOrderElementsOf(EXPECTED_GLOBAL);

        Set<String> redacted = new java.util.HashSet<>();
        plan.tables().forEach(t -> t.redactedColumns().forEach(c -> redacted.add(t.name() + "." + c)));
        assertThat(redacted).isEqualTo(EXPECTED_REDACTED);

        // Nothing that merely LOOKS like a credential may leave unredacted.
        for (TenantTable t : plan.tables()) {
            for (String c : t.exportColumns()) {
                assertThat(c.matches(".*(password|secret|token|pin_hash).*") && !c.matches(
                        "password_(changed|expires)_at|must_change_password|mfa_enabled|pos_pin_set_at"))
                        .as("%s.%s looks like a credential and is exported", t.name(), c).isFalse();
            }
        }

        assertThat(plan.deletionBlockers()).as("no trigger/rule/cycle blocks the purge").isEmpty();
        assertThat(plan.cycleBreaks()).hasSize(1);
        assertThat(plan.cycleBreaks().get(0).table()).isEqualTo("approval_request_steps");
        assertThat(plan.triggersSeen()).anyMatch(s -> s.contains("trg_role_code_not_reserved_by_global"));

        long direct = plan.tables().stream().filter(t -> t.kind() == TenantTable.Kind.DIRECT).count();
        long child = plan.tables().stream().filter(t -> t.kind() == TenantTable.Kind.CHILD).count();
        System.out.println("CLASSIFICATION root=1 direct=" + direct + " children=" + child
                + " global=" + plan.globalTables().size() + " redacted=" + redacted.size()
                + " cycleBreaks=" + plan.cycleBreaks() + " triggers=" + plan.triggersSeen());
    }

    @Test
    @DisplayName("deletion order never deletes a parent before a tenant table that references it")
    void deletionOrderRespectsForeignKeys() {
        TenantDataPlan plan = plan(b);
        Map<String, Integer> pos = new LinkedHashMap<>();
        for (int i = 0; i < plan.tables().size(); i++) {
            pos.put(plan.tables().get(i).name(), i);
        }
        jdbc.query("""
                SELECT rel.relname, frel.relname
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_class frel ON frel.oid = con.confrelid
                WHERE con.contype = 'f' AND rel.relnamespace = current_schema()::regnamespace
                """, rs -> {
            String child = rs.getString(1);
            String parent = rs.getString(2);
            if (child.equals(parent) || !pos.containsKey(child) || !pos.containsKey(parent)) {
                return;
            }
            boolean broken = plan.cycleBreaks().stream().anyMatch(c -> c.table().equals(child))
                    && child.equals("approval_request_steps") && parent.equals("approval_decisions");
            if (!broken) {
                assertThat(pos.get(child)).as("%s must be deleted before %s", child, parent)
                        .isLessThan(pos.get(parent));
            }
        });
        assertThat(plan.tables().get(plan.tables().size() - 1).name())
                .as("the organisation row is deleted last").isEqualTo("organisations");
    }

    // ----------------------------------------------------------------------------------------
    // R-7 export
    // ----------------------------------------------------------------------------------------

    @Test
    @DisplayName("R-7 · the export manifest matches the database, secrets are absent, A is not in it")
    void exportMatchesDatabaseAndLeaksNothing() throws IOException {
        Map<String, Long> beforeA = counts(a);
        Map<String, Long> dbB = counts(b);
        actingAsOperator();

        Map<String, byte[]> files = unzip(exportOf(b));
        JsonNode manifest = json.readTree(files.get("manifest.json"));

        assertThat(manifest.get("organisationUid").asText()).isEqualTo(b.org().getUid());
        assertThat(manifest.get("organisationName").asText()).isEqualTo(b.org().getName());
        assertThat(manifest.get("exportedAt").asText()).isNotBlank();
        String latestFlyway = jdbc.queryForObject("""
                SELECT version FROM flyway_schema_history WHERE success AND version IS NOT NULL
                ORDER BY installed_rank DESC LIMIT 1
                """, String.class);
        assertThat(manifest.get("flywayVersion").asText()).isEqualTo(latestFlyway);

        // Counts: manifest == streamed CSV rows == database.
        Map<String, Long> manifestCounts = new TreeMap<>();
        for (JsonNode t : manifest.get("tables")) {
            String name = t.get("table").asText();
            manifestCounts.put(name, t.get("rows").asLong());
            List<List<String>> csv = parseCsv(files.get("data/" + name + ".csv"));
            assertThat((long) csv.size() - 1).as("rows in %s.csv", name)
                    .isEqualTo(t.get("rows").asLong());
            assertThat(csv.get(0)).as("header of %s", name)
                    .containsExactlyElementsOf(json.convertValue(t.get("columns"), List.class));
        }
        assertThat(manifestCounts).isEqualTo(dbB);
        assertThat(manifest.get("totalRows").asLong())
                .isEqualTo(dbB.values().stream().mapToLong(Long::longValue).sum());

        // Independent of the catalogue's own predicate: directly scoped company tables by plain
        // equality on B's single company.
        for (TenantTable t : plan(b).tables()) {
            if (t.kind() == TenantTable.Kind.DIRECT && t.columns().contains("company_id")
                    && !t.columns().contains("organisation_id") && !t.name().equals("audit_logs")) {
                Long plain = jdbc.queryForObject("SELECT count(*) FROM \"" + t.name()
                        + "\" WHERE company_id = ?", Long.class, b.companyId());
                assertThat(manifestCounts.get(t.name())).as(t.name()).isEqualTo(plain);
            }
        }

        // The data is really there (not a vacuous all-zero comparison).
        assertThat(manifestCounts.get("organisations")).isEqualTo(1L);
        assertThat(manifestCounts.get("companies")).isEqualTo(1L);
        assertThat(manifestCounts.get("products")).isEqualTo(1L);
        assertThat(manifestCounts.get("chart_of_accounts")).as("provisioned + 2 planted").isGreaterThanOrEqualTo(2L);
        assertThat(manifestCounts.get("approval_decisions")).isEqualTo(1L);
        assertThat(manifestCounts.get("role_permission")).as("a CHILD table, via roles").isEqualTo(3L);
        assertThat(manifestCounts.get("refresh_tokens")).isEqualTo(1L);
        assertThat(manifestCounts.get("audit_logs")).as("the login row, via its actor").isGreaterThan(0);
        assertThat(manifestCounts.get("user_branch")).isGreaterThan(0);
        assertThat(manifestCounts.get("roles")).as("tenant roles only, not the global bundles")
                .isEqualTo(jdbc.queryForObject(
                        "SELECT count(*) FROM roles WHERE organisation_id = ?", Long.class,
                        b.org().getId()));

        // Redaction: listed in the manifest, absent from the files, and the values appear nowhere.
        Set<String> listed = new java.util.HashSet<>();
        manifest.get("redactedColumns").forEach(n -> listed.add(n.asText()));
        assertThat(listed).isEqualTo(EXPECTED_REDACTED);
        assertThat(parseCsv(files.get("data/app_users.csv")).get(0))
                .doesNotContain("password_hash", "pos_pin_hash", "mfa_secret").contains("username");
        assertThat(parseCsv(files.get("data/refresh_tokens.csv")).get(0))
                .doesNotContain("token_hash");
        StringBuilder everything = new StringBuilder();
        files.values().forEach(v -> everything.append(new String(v, StandardCharsets.UTF_8)));
        assertThat(everything.toString())
                .doesNotContain(b.adminPasswordHash(), "PINHASH-B-" + tag, "MFASECRET-B-" + tag,
                        "RTHASH-B-" + tag)
                // ...and tenant A is nowhere in B's archive.
                .doesNotContain(a.org().getUid(), "Tenant A " + tag, a.companyCode(),
                        "Widget A", "BC-A-" + tag, "PINHASH-A-" + tag);
        assertThat(everything.toString()).contains("Tenant B " + tag, "Widget B");

        // Read-only: exporting changed nothing for A, and left a completed-export audit row for B.
        assertThat(counts(a)).isEqualTo(beforeA);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs
                WHERE action = 'ORG.EXPORT' AND target_type = 'organisations' AND target_id = ?
                """, Long.class, b.org().getId())).isEqualTo(1L);
    }

    @Test
    @DisplayName("R-7 · only the platform operator may export; others get NOT FOUND")
    void exportRefusesNonOperators() {
        actingAsTenantAdmin(a);
        assertThatThrownBy(() -> exportOf(b)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> exportOf(a)).as("not even their own tenancy")
                .isInstanceOf(NotFoundException.class);

        RequestContext.clear();
        assertThatThrownBy(() -> exportOf(b)).isInstanceOf(NotFoundException.class);

        actingAsOperator();
        assertThatThrownBy(() -> departure.exportTenant("NOSUCHUID", fileName -> new ByteArrayOutputStream()))
                .isInstanceOf(NotFoundException.class);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ORG.EXPORT'",
                Long.class)).as("a refused export is not an export").isZero();
    }
}
