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

    // ----------------------------------------------------------------------------------------
    // R-8 purge
    // ----------------------------------------------------------------------------------------

    private void suspend(Tenant t) {
        actingAsOperator();
        organisationService.setStatus(t.org().getUid(), false);
    }

    private void resume(Tenant t) {
        actingAsOperator();
        organisationService.setStatus(t.org().getUid(), true);
    }

    /** There is no suspended-at column; the rule reads the audit row, so the test moves THAT. */
    private void backdateSuspension(Tenant t, int days) {
        jdbc.update("UPDATE audit_logs SET at = now() - (? * interval '1 day') "
                + "WHERE action = 'ORG.SUSPEND' AND target_type = 'organisations' AND target_id = ?",
                days, t.org().getId());
    }

    private com.erp.modules.iam.domain.dto.TenantPurgeReportDto purge(Tenant t, Boolean dryRun,
                                                                      String confirm) {
        return departure.purgeTenant(t.org().getUid(),
                new com.erp.modules.iam.domain.dto.PurgeTenantRequestDto(dryRun, confirm));
    }

    /** Moves the {@code nth} (0-based, by id) audit row of {@code action} about B to N days ago. */
    private void setAuditAge(String action, int nth, int days) {
        List<Long> ids = jdbc.queryForList("SELECT id FROM audit_logs WHERE action = ? "
                + "AND target_type = 'organisations' AND target_id = ? ORDER BY id", Long.class,
                action, b.org().getId());
        jdbc.update("UPDATE audit_logs SET at = now() - (? * interval '1 day') WHERE id = ?",
                days, ids.get(nth));
    }

    /** Suspended 31 days ago, then exported: every departure rule satisfied. */
    private void makeEligible(Tenant t) throws IOException {
        suspend(t);
        backdateSuspension(t, 31);
        actingAsOperator();
        exportOf(t);
    }

    /** Row text digest per tenant table: byte-for-byte comparison of a tenant's data. */
    private Map<String, String> digests(Tenant t) {
        Map<String, String> out = new TreeMap<>();
        for (TenantTable table : plan(t).tables()) {
            out.put(table.name(), jdbc.queryForObject("SELECT coalesce(md5(string_agg(x::text, '|' "
                    + "ORDER BY x::text)), '-') FROM \"" + table.name() + "\" x WHERE "
                    + table.rowFilterSql(), String.class));
        }
        return out;
    }

    /** Total rows per table in the whole database (every tenant, plus global). */
    private Map<String, Long> totals() {
        Map<String, Long> out = new TreeMap<>();
        for (String t : jdbc.queryForList("""
                SELECT table_name FROM information_schema.tables
                WHERE table_schema = current_schema() AND table_type = 'BASE TABLE'
                  AND table_name <> 'flyway_schema_history'
                """, String.class)) {
            out.put(t, jdbc.queryForObject("SELECT count(*) FROM \"" + t + "\"", Long.class));
        }
        return out;
    }

    @Test
    @DisplayName("R-8 · every refusal holds, and each one leaves B exactly as it was")
    void purgeIsRefusedUnlessEveryRuleHolds() throws IOException {
        Map<String, Long> beforeB = counts(b);
        Map<String, Long> beforeAll = totals();
        String name = b.org().getName();

        // 1. not suspended
        actingAsOperator();
        assertThatThrownBy(() -> purge(b, false, name))
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class)
                .hasMessageContaining("suspended");
        var dry = purge(b, true, null);
        assertThat(dry.eligible()).isFalse();
        assertThat(dry.blockers()).anyMatch(s -> s.contains("suspended first"));

        // 2. suspended, but only just, and never exported
        suspend(b);
        actingAsOperator();
        assertThatThrownBy(() -> purge(b, false, name))
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class)
                .hasMessageContaining("30 days");
        assertThat(purge(b, true, null).blockers()).anyMatch(s -> s.contains("30 days"));

        // 3. past 30 days, but no export after the suspension
        backdateSuspension(b, 31);
        actingAsOperator();
        assertThatThrownBy(() -> purge(b, false, name))
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class)
                .hasMessageContaining("export");

        // 4. suspend (50d ago) -> export (40d ago) -> resume (35d ago) -> suspend again (31d ago).
        //    Past 30 days and exported, but the only export predates the LATEST suspension.
        exportOf(b);
        resume(b);
        suspend(b);
        setAuditAge("ORG.SUSPEND", 0, 50);
        setAuditAge("ORG.EXPORT", 0, 40);
        setAuditAge("ORG.RESUME", 0, 35);
        setAuditAge("ORG.SUSPEND", 1, 31);
        actingAsOperator();
        assertThatThrownBy(() -> purge(b, false, name))
                .as("an export made BEFORE the latest suspension is not a departure export")
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class)
                .hasMessageContaining("export");

        // 5. a fresh export after that suspension: everything holds except the confirmation name
        exportOf(b);
        actingAsOperator();
        assertThat(purge(b, true, null).eligible()).isTrue();
        assertThatThrownBy(() -> purge(b, false, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purge(b, false, name.toLowerCase()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> purge(b, false, name + " "))
                .isInstanceOf(IllegalArgumentException.class);

        // 6. the caller's own organisation (a root principal whose home is B)...
        RequestContext.set(new RequestContext.Principal(operator.getId(), "operator", true,
                b.companyId(), b.branchId(), "127.0.0.1", b.org().getId()));
        assertThatThrownBy(() -> purge(b, false, name))
                .as("own organisation, even when every other rule holds")
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class);
        // ...the platform organisation, from a root principal whose home is elsewhere...
        RequestContext.set(new RequestContext.Principal(operator.getId(), "operator", true,
                a.companyId(), a.branchId(), "127.0.0.1", a.org().getId()));
        assertThatThrownBy(() -> departure.purgeTenant(platform.getUid(),
                new com.erp.modules.iam.domain.dto.PurgeTenantRequestDto(false, platform.getName())))
                .as("the platform operator's organisation")
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class);
        actingAsOperator();
        // ...and a tenant that merely contains a root user counts as a platform organisation too.
        AppUser rootInA = new AppUser("rootina-" + tag, passwordEncoder.encode("Operator!Pass123"),
                "Root In A");
        rootInA.setOrganisationId(a.org().getId());
        rootInA.setRoot(true);
        users.save(rootInA);
        assertThatThrownBy(() -> departure.purgeTenant(a.org().getUid(),
                new com.erp.modules.iam.domain.dto.PurgeTenantRequestDto(true, null)))
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class);

        // 7. a caller who is not the platform operator
        actingAsTenantAdmin(a);
        assertThatThrownBy(() -> purge(b, false, name)).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> purge(b, true, null)).isInstanceOf(NotFoundException.class);
        RequestContext.clear();
        assertThatThrownBy(() -> purge(b, false, name)).isInstanceOf(NotFoundException.class);

        // Nothing moved: B's rows are what they were (audit rows aside, which the test itself and
        // the suspend/export calls legitimately added), and no purge row exists.
        Map<String, Long> afterB = counts(b);
        afterB.keySet().stream().filter(k -> !k.equals("audit_logs"))
                .forEach(k -> assertThat(afterB.get(k)).as(k).isEqualTo(beforeB.get(k)));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ORG.PURGE'",
                Long.class)).isZero();
        Map<String, Long> afterAll = totals();
        afterAll.keySet().stream().filter(k -> !k.equals("audit_logs") && !k.equals("app_users"))
                .forEach(k -> assertThat(afterAll.get(k)).as(k).isEqualTo(beforeAll.get(k)));
    }

    @Test
    @DisplayName("R-8 · a dry run reports the counts in deletion order and deletes nothing")
    void dryRunDeletesNothing() throws IOException {
        makeEligible(b);
        Map<String, Long> dbB = counts(b);
        Map<String, Long> allBefore = totals();
        Map<String, String> digestA = digests(a);
        Map<String, String> digestB = digests(b);
        actingAsOperator();

        for (var report : List.of(purge(b, true, null),
                departure.purgeTenant(b.org().getUid(), null),
                purge(b, null, null))) {
            assertThat(report.dryRun()).isTrue();
            assertThat(report.eligible()).isTrue();
            assertThat(report.blockers()).isEmpty();
            assertThat(report.tables().stream().map(t -> t.table()).toList())
                    .as("deletion order").isEqualTo(
                            plan(b).tables().stream().map(TenantTable::name).toList());
            Map<String, Long> reported = new TreeMap<>();
            report.tables().forEach(t -> reported.put(t.table(), t.rows()));
            assertThat(reported).isEqualTo(dbB);
            assertThat(report.totalRows())
                    .isEqualTo(dbB.values().stream().mapToLong(Long::longValue).sum());
            assertThat(report.tables().get(report.tables().size() - 1).table())
                    .isEqualTo("organisations");
        }

        assertThat(totals()).as("a dry run changes no table").isEqualTo(allBefore);
        assertThat(digests(a)).isEqualTo(digestA);
        assertThat(digests(b)).isEqualTo(digestB);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ORG.PURGE'",
                Long.class)).isZero();
    }

    @Test
    @DisplayName("R-8 · a real purge removes every row of B, none of A's, and leaves the ORG.PURGE row")
    void purgeRemovesOnlyTheTenant() throws IOException {
        makeEligible(b);
        TenantDataPlan planB = plan(b);
        Map<String, Long> dbB = counts(b);
        Map<String, Long> totalsBefore = totals();
        Map<String, String> digestA = digests(a);
        Map<String, Long> countsA = counts(a);
        Long platformUsers = jdbc.queryForObject(
                "SELECT count(*) FROM app_users WHERE organisation_id = ?", Long.class,
                platform.getId());
        Long globalRoles = jdbc.queryForObject(
                "SELECT count(*) FROM roles WHERE organisation_id IS NULL", Long.class);
        Long permissions = jdbc.queryForObject("SELECT count(*) FROM permissions", Long.class);

        // The self-reference and the mutual cycle really are populated, so they are really tested.
        assertThat(dbB.get("approval_request_steps")).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM chart_of_accounts WHERE parent_id IS NOT "
                + "NULL AND company_id = ?", Long.class, b.companyId())).isGreaterThan(0);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM approval_request_steps WHERE "
                + "resolving_decision_id IS NOT NULL AND company_id = ?", Long.class, b.companyId()))
                .isGreaterThan(0);

        actingAsOperator();
        var report = purge(b, false, b.org().getName());

        assertThat(report.dryRun()).isFalse();
        Map<String, Long> deleted = new TreeMap<>();
        report.tables().forEach(t -> deleted.put(t.table(), t.rows()));
        assertThat(deleted).as("rows deleted == rows the dry run counted").isEqualTo(dbB);

        // Every table in the whole database lost exactly B's rows - no more, no fewer.
        Map<String, Long> totalsAfter = totals();
        for (TenantTable t : planB.tables()) {
            long expected = totalsBefore.get(t.name()) - dbB.get(t.name());
            if (t.name().equals("audit_logs")) {
                expected += 1;   // the surviving ORG.PURGE row
            }
            assertThat(totalsAfter.get(t.name())).as("rows left in %s", t.name()).isEqualTo(expected);
        }
        planB.globalTables().forEach(g -> assertThat(totalsAfter.get(g)).as(g)
                .isEqualTo(totalsBefore.get(g)));

        // B is gone by its own identity, too.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM organisations WHERE id = ?", Long.class,
                b.org().getId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM companies WHERE id = ?", Long.class,
                b.companyId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE id = ?", Long.class,
                b.adminId())).isZero();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM app_users WHERE username LIKE '%@' || ?", Long.class,
                b.org().getAlias())).as("the <user>@<alias> usernames are free again").isZero();

        // A is byte-for-byte what it was; so is everything global and the platform's own.
        assertThat(counts(a)).isEqualTo(countsA);
        assertThat(digests(a)).isEqualTo(digestA);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM app_users WHERE organisation_id = ?",
                Long.class, platform.getId())).isEqualTo(platformUsers);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM roles WHERE organisation_id IS NULL",
                Long.class)).isEqualTo(globalRoles);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM permissions", Long.class))
                .isEqualTo(permissions);

        // The ORG.PURGE row survived: who, when, which organisation, per-table counts, no dangling FK.
        Map<String, Object> row = jdbc.queryForMap("""
                SELECT actor_user_id, target_type, target_id, target_uid, at,
                       detail ->> 'organisationUid' AS uid, detail ->> 'organisationName' AS name,
                       detail ->> 'totalRows' AS total,
                       detail -> 'rowsDeletedByTable' ->> 'products' AS products
                FROM audit_logs WHERE action = 'ORG.PURGE'
                """);
        assertThat(row.get("actor_user_id")).isEqualTo(operator.getId());
        assertThat(row.get("target_type")).isEqualTo("organisations");
        assertThat(row.get("target_id")).isEqualTo(b.org().getId());
        assertThat(row.get("target_uid")).isEqualTo(b.org().getUid());
        assertThat(row.get("at")).isNotNull();
        assertThat(row.get("uid")).isEqualTo(b.org().getUid());
        assertThat(row.get("name")).isEqualTo(b.org().getName());
        assertThat(row.get("total")).isEqualTo(String.valueOf(report.totalRows()));
        assertThat(row.get("products")).isEqualTo("1");
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs l JOIN app_users u ON u.id = l.actor_user_id
                WHERE l.action = 'ORG.PURGE'
                """, Long.class)).as("the actor is the surviving operator").isEqualTo(1L);
        // The platform's lifecycle trail about B survived too.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM audit_logs WHERE target_type = 'organisations' AND target_id = ?
                  AND action IN ('ORG.SUSPEND', 'ORG.EXPORT')
                """, Long.class, b.org().getId())).isEqualTo(2L);

        // B's uid names nothing now.
        assertThatThrownBy(() -> purge(b, true, null)).isInstanceOf(NotFoundException.class);
    }

    @Test
    @DisplayName("R-8 · if the database refuses one delete, NOTHING is deleted")
    void aLateFailureRollsBackEverything() throws IOException {
        makeEligible(b);
        Map<String, Long> totalsBefore = totals();
        Map<String, String> digestB = digests(b);

        // A row of tenant A that points at B's administrator. Isolation says this cannot happen;
        // the point is that if it ever does, the user delete - one of the LAST statements - fails
        // and the earlier deletes of hundreds of rows must roll back with it.
        int planted = jdbc.update("UPDATE user_company SET assigned_by = ? WHERE company_id = ?",
                b.adminId(), a.companyId());
        assertThat(planted).as("the cross-tenant reference exists").isGreaterThan(0);
        Map<String, Long> totalsWithPlant = totals();

        actingAsOperator();
        assertThatThrownBy(() -> purge(b, false, b.org().getName()))
                .isInstanceOf(com.erp.platform.common.api.ConflictException.class)
                .hasMessageContaining("Nothing was deleted");

        assertThat(totals()).as("rolled back in full").isEqualTo(totalsWithPlant);
        assertThat(totalsWithPlant).isEqualTo(totalsBefore);
        assertThat(digests(b)).isEqualTo(digestB);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE action = 'ORG.PURGE'",
                Long.class)).as("no purge row for a purge that did not happen").isZero();
    }

    @Test
    @DisplayName("R-8 · a trigger that fires on DELETE blocks the purge; triggers are never disabled")
    void deleteTriggerBlocksPurge() throws IOException {
        makeEligible(b);
        Map<String, Long> totalsBefore = totals();
        jdbc.execute("CREATE FUNCTION tmp_block_branch_delete() RETURNS trigger LANGUAGE plpgsql AS "
                + "$$ BEGIN RAISE EXCEPTION 'blocked'; END $$");
        jdbc.execute("CREATE TRIGGER tmp_block_branch_delete BEFORE DELETE ON branches "
                + "FOR EACH ROW EXECUTE FUNCTION tmp_block_branch_delete()");
        try {
            assertThat(plan(b).deletionBlockers()).anyMatch(s -> s.contains("tmp_block_branch_delete"));
            actingAsOperator();
            var dry = purge(b, true, null);
            assertThat(dry.eligible()).isFalse();
            assertThatThrownBy(() -> purge(b, false, b.org().getName()))
                    .isInstanceOf(com.erp.platform.common.api.ConflictException.class);
            assertThat(totals()).isEqualTo(totalsBefore);
            assertThat(jdbc.queryForObject(
                    "SELECT tgenabled::text FROM pg_trigger WHERE tgname = 'tmp_block_branch_delete'",
                    String.class)).as("still enabled: purge never disables a trigger").isEqualTo("O");
        } finally {
            jdbc.execute("DROP TRIGGER tmp_block_branch_delete ON branches");
            jdbc.execute("DROP FUNCTION tmp_block_branch_delete()");
        }
    }
}
