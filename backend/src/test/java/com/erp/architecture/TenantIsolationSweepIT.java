package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.platform.bootstrap.TenantProvisioningService;
import com.erp.platform.security.PermissionResolver;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.jwt.JwtService;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.MethodParameter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ValueConstants;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.mvc.method.RequestMappingInfo;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * R-3(b) — the cross-tenant sweep, the runtime half of the compensating controls that replace
 * row-level security (D-4 rejected 2026-10-31; MULTITENANCY-PLAN.md §14.2). The build-time half is
 * {@link TenantScopedRepositoryRulesTest}.
 *
 * <h2>What it does</h2>
 *
 * Two tenants are provisioned through the real {@link TenantProvisioningService} (API shape: a
 * NON-root ORG_ADMIN administrator, plus price list, walk-in customer and till, so both have real
 * rows). Every {@code uid} that belongs to tenant B is harvested from the database: every table in
 * {@code information_schema} with a {@code uid} column and a {@code company_id} or
 * {@code organisation_id}, plus the organisation and company rows themselves.
 *
 * <p>Every {@code GET} handler is then enumerated from {@link RequestMappingHandlerMapping} and
 * driven through the real filter chain as tenant A's non-root administrator (root bypasses
 * permissions and would mask gaps):
 * <ol>
 *   <li><b>Own-scope sweep.</b> Every GET that has no path variable, required query params filled
 *       with A's own values. The body — whatever the status — must contain no B uid.
 *   <li><b>Hostile-parameter sweep.</b> The same endpoints again, but every required
 *       company/branch/organisation/user param is B's. This is the confused-deputy attack: 89
 *       controllers take {@code companyId} from the caller. A 2xx body must hold no B uid.
 *   <li><b>Uid-path sweep.</b> Every GET whose path variables are all uids, called with one B uid
 *       per harvested table. Either a non-2xx status or a body with no B uid.
 * </ol>
 *
 * <h2>Why it cannot pass vacuously</h2>
 *
 * A calibration test runs the same own-scope sweep as B's own administrator and requires that B's
 * uids DO show up in responses — proving the harvested uids and the response scan agree.
 *
 * <h2>Cost control</h2>
 *
 * The uid-path sweep is endpoints x harvested tables, with ONE (the lowest-id) uid per table, and
 * requests are issued sequentially on one thread. Bodies are scanned by tokenising for 26-character
 * identifiers and probing a hash set, not by one {@code contains} per uid.
 *
 * <h2>What is NOT covered (the skip lists below carry the reasons)</h2>
 *
 * Endpoints with a non-uid path variable, and non-/api mappings. Tables that carry neither
 * {@code company_id} nor {@code organisation_id} (child rows) contribute no uids of their own.
 */
@AutoConfigureMockMvc
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("two-tenant sweep — tenant A's administrator must never read a tenant-B uid")
class TenantIsolationSweepIT extends PostgresIntegrationTest {

    private static final Pattern PATH_VAR = Pattern.compile("\\{([^}/:]+)(?::[^}]*)?}");
    private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9]{26}");

    /**
     * Endpoints that cannot be driven generically. Key is {@code "GET /pattern"}; value the reason.
     * Everything not listed here AND not skipped structurally (see {@link #classify}) is called.
     */
    private static final Map<String, String> SKIPPED = new TreeMap<>();

    static {
        // (intentionally empty until the sweep proves one is needed)
    }

    @Autowired private MockMvc mockMvc;
    @Autowired private com.fasterxml.jackson.databind.ObjectMapper json;
    @Autowired @Qualifier("requestMappingHandlerMapping") private RequestMappingHandlerMapping mappings;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private IamTestData testData;
    @Autowired private TenantProvisioningService provisioning;
    @Autowired private PermissionResolver permissionResolver;
    @Autowired private JwtService jwtService;
    @Autowired private AppUserRepository users;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;

    /** Everything the sweep needs to know about one tenant. */
    private static final class Tenant {
        Long orgId;
        Long companyId;
        Long branchId;
        String orgUid;
        String companyUid;
        String branchUid;
        String adminUid;
        String adminToken;
        /** table -> uids of this tenant's rows. */
        final Map<String, List<String>> uidsByTable = new TreeMap<>();
        /** table -> ids of this tenant's rows (first row), for numeric params. */
        final Map<String, Long> firstIdByTable = new HashMap<>();
        final Set<String> allUids = new HashSet<>();
    }

    private Tenant tenantA;
    private Tenant tenantB;
    private boolean ready;
    private final DefaultParameterNameDiscoverer names = new DefaultParameterNameDiscoverer();

    @BeforeEach
    void provisionOnce() {
        if (ready) {
            return;
        }
        testData.clearAll();
        RequestContext.clear();
        tenantA = provision("A");
        tenantB = provision("B");
        permissionResolver.invalidate();
        seed(tenantA);
        seed(tenantB);
        ready = true;
    }

    @AfterAll
    void cleanUp() {
        RequestContext.clear();
        testData.clearAll();
    }

    private Tenant provision(String label) {
        String tag = UUID.randomUUID().toString().substring(0, 8);
        var p = provisioning.provision(new TenantProvisioningService.NewTenantRequest(
                "Sweep " + label + " " + tag, "Africa/Dar_es_Salaam",
                "SW" + label + tag, "Sweep " + label + " Co",
                null, null, null,
                "SB" + label + tag, "Sweep " + label + " Branch",
                "orgadmin", "OrgPass12345", "Sweep " + label + " Admin",
                "TZS", "TZS", List.of("TZS"),
                "STD" + label, "Standard " + label, Boolean.FALSE,
                "Walk-in " + label, "Till " + label,
                true, false));   // API shape: composed username, NOT a platform operator
        Tenant t = new Tenant();
        t.orgId = p.organisationId();
        t.companyId = p.companyId();
        t.branchId = p.branchId();
        AppUser admin = users.findById(p.adminUserId()).orElseThrow();
        assertThat(admin.isRoot()).as("the sweep must run as a NON-root administrator").isFalse();
        t.adminUid = admin.getUid();
        t.orgUid = organisations.findById(t.orgId).orElseThrow().getUid();
        t.companyUid = companies.findById(t.companyId).orElseThrow().getUid();
        t.branchUid = branches.findById(t.branchId).orElseThrow().getUid();
        t.adminToken = jwtService.issueAccessToken(admin, t.companyId, t.branchId).value();
        return t;
    }

    // ---------------------------------------------------------------------
    // Harvest
    // ---------------------------------------------------------------------

    private void harvest(Tenant t) {
        List<Map<String, Object>> cols = jdbc.queryForList(
                "SELECT table_name, column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'public' AND column_name IN "
                        + "('uid','company_id','organisation_id','id')");
        Map<String, Set<String>> byTable = new TreeMap<>();
        for (Map<String, Object> c : cols) {
            byTable.computeIfAbsent((String) c.get("table_name"), k -> new HashSet<>())
                    .add((String) c.get("column_name"));
        }

        // WHERE clause that selects this tenant's rows, per table. Longs are inlined: they are
        // ids this test itself just created, never input.
        Map<String, String> where = new TreeMap<>();
        for (var e : byTable.entrySet()) {
            Set<String> have = e.getValue();
            if (have.contains("company_id") && have.contains("organisation_id")) {
                where.put(e.getKey(), "company_id = " + t.companyId + " OR organisation_id = " + t.orgId);
            } else if (have.contains("company_id")) {
                where.put(e.getKey(), "company_id = " + t.companyId);
            } else if (have.contains("organisation_id")) {
                where.put(e.getKey(), "organisation_id = " + t.orgId);
            }
        }
        where.put("organisations", "id = " + t.orgId);

        // Child tables (invoice lines, addresses, contacts...) carry neither column. They are the
        // tenant's rows all the same, reached through a foreign key to a table that is scoped, to
        // any depth. Without this their uids would be invisible to the sweep, and the endpoints that
        // serve them by parent uid would be "clean" for want of anything to look for.
        List<Map<String, Object>> fks = jdbc.queryForList(
                "SELECT c.conrelid::regclass::text AS child, a.attname AS child_col, "
                        + "c.confrelid::regclass::text AS parent "
                        + "FROM pg_constraint c JOIN pg_attribute a "
                        + "ON a.attrelid = c.conrelid AND a.attnum = c.conkey[1] "
                        + "WHERE c.contype = 'f' AND array_length(c.conkey, 1) = 1 "
                        + "AND c.connamespace = 'public'::regnamespace");
        for (int round = 0; round < 6; round++) {
            boolean grew = false;
            for (Map<String, Object> fk : fks) {
                String child = ((String) fk.get("child")).replace("\"", "").replace("public.", "");
                String parent = ((String) fk.get("parent")).replace("\"", "").replace("public.", "");
                Set<String> childCols = byTable.get(child);
                Set<String> parentCols = byTable.get(parent);
                if (childCols == null || !childCols.contains("uid") || where.containsKey(child)
                        || parentCols == null || !parentCols.contains("id")
                        || !where.containsKey(parent)) {
                    continue;
                }
                where.put(child, "\"" + fk.get("child_col") + "\" IN (SELECT id FROM \"" + parent
                        + "\" WHERE " + where.get(parent) + ")");
                grew = true;
            }
            if (!grew) {
                break;
            }
        }

        for (var e : where.entrySet()) {
            String table = e.getKey();
            Set<String> have = byTable.get(table);
            if (have == null || !have.contains("uid")) {
                continue;
            }
            String idCol = have.contains("id") ? "id" : "uid";
            List<Map<String, Object>> rows = jdbc.queryForList(
                    "SELECT " + idCol + " AS rid, uid FROM \"" + table + "\" WHERE " + e.getValue()
                            + " ORDER BY " + idCol);
            if (rows.isEmpty()) {
                continue;
            }
            List<String> uids = new ArrayList<>();
            for (Map<String, Object> r : rows) {
                String uid = (String) r.get("uid");
                if (uid != null) {
                    uids.add(uid);
                }
            }
            t.uidsByTable.put(table, uids);
            t.allUids.addAll(uids);
            if (have.contains("id") && rows.get(0).get("rid") instanceof Number n) {
                t.firstIdByTable.put(table, n.longValue());
            }
        }
    }

    // ---------------------------------------------------------------------
    // Seeding through the real API
    // ---------------------------------------------------------------------

    /**
     * Gives the tenant rows beyond what provisioning creates (product, supplier, orders...), by
     * POSTing to the real endpoints as the tenant's own administrator. Best effort and loud: a step
     * that fails is printed and skipped, because an unseeded aggregate only narrows what the sweep
     * can prove — the calibration tests state how much that is.
     */
    private void seed(Tenant t) {
        harvest(t);   // units of measure etc. exist already; their uids feed the calls below
        String unit = first(t, "units_of_measure");
        post(t, "/api/v1/payment-terms", Map.of("companyId", t.companyId, "code", "NET30",
                "name", "Net 30", "basis", "DAYS_AFTER_INVOICE", "netDays", 30));
        String product = uid(post(t, "/api/v1/products", Map.of("companyUid", t.companyUid,
                "code", "SWP-1", "name", "Sweep product", "type", "GOODS", "sellable", true,
                "stockable", true, "baseUnitUid", unit)));
        String supplier = uid(post(t, "/api/v1/suppliers", Map.of("companyId", t.companyId,
                "partyType", "BUSINESS", "displayName", "Sweep supplier", "tin", "123-456-789",
                "supplierKind", "GOODS")));
        String customer = uid(post(t, "/api/v1/customers", Map.of("companyId", t.companyId,
                "partyType", "BUSINESS", "displayName", "Sweep customer", "tin", "987-654-321",
                "customerKind", "CREDIT_ACCOUNT")));
        Map<String, Object> addr = Map.of("addressRole", "BILL_TO", "isDefault", true,
                "country", "TZ", "addressLine1", "1 Sweep Road", "city", "Dar");
        Map<String, Object> contact = Map.of("contactPerson", "Sweep Person",
                "phone", "0700000000", "isPrimary", true);
        if (customer != null) {
            post(t, "/api/v1/customers/uid/" + customer + "/addresses", addr);
            post(t, "/api/v1/customers/uid/" + customer + "/contacts", contact);
        }
        if (supplier != null) {
            post(t, "/api/v1/suppliers/uid/" + supplier + "/addresses", addr);
            post(t, "/api/v1/suppliers/uid/" + supplier + "/contacts", contact);
        }
        if (product != null && customer != null) {
            String so = uid(post(t, "/api/v1/sales-orders", Map.of("companyUid", t.companyUid,
                    "customerUid", customer, "currency", "TZS",
                    "orderDate", LocalDate.now().toString())));
            if (so != null) {
                post(t, "/api/v1/sales-orders/uid/" + so + "/lines", Map.of("productUid", product,
                        "unitUid", unit, "quantity", 2, "unitPriceOverride", 1000));
            }
            String inv = uid(post(t, "/api/v1/sales-invoices", Map.of("companyUid", t.companyUid,
                    "customerUid", customer, "currency", "TZS")));
            if (inv != null) {
                post(t, "/api/v1/sales-invoices/uid/" + inv + "/lines", Map.of("productUid", product,
                        "unitUid", unit, "quantity", 1, "unitPriceOverride", 500));
            }
        }
        if (product != null && supplier != null) {
            post(t, "/api/v1/purchase-orders", Map.of("companyUid", t.companyUid,
                    "supplierUid", supplier, "currency", "TZS",
                    "expectedDate", LocalDate.now().plusDays(7).toString(),
                    "lines", List.of(Map.of("productUid", product, "unitUid", unit,
                            "orderedQty", 5, "unitCostAmount", 100, "note", "sweep"))));
        }
        post(t, "/api/v1/roles", Map.of("code", "SWEEP_ROLE", "name", "Sweep role",
                "description", "tenant-owned role"));
        post(t, "/api/v1/hr/departments", Map.of("code", "SWD", "name", "Sweep department"));
        post(t, "/api/v1/other-parties", Map.of("companyId", t.companyId, "partyType", "INDIVIDUAL",
                "displayName", "Sweep other party"));
        post(t, "/api/v1/crm/leads", Map.of("companyId", t.companyId, "displayName", "Sweep lead",
                "leadSource", "OTHER"));
        if (product != null && customer != null) {
            post(t, "/api/v1/quotations", Map.of("companyUid", t.companyUid,
                    "customerUid", customer, "currency", "TZS",
                    "quoteDate", LocalDate.now().toString(),
                    "validUntil", LocalDate.now().plusDays(30).toString()));
        }
        t.uidsByTable.clear();
        t.firstIdByTable.clear();
        t.allUids.clear();
        harvest(t);
    }

    private static String first(Tenant t, String table) {
        List<String> l = t.uidsByTable.get(table);
        return l == null || l.isEmpty() ? null : l.get(0);
    }

    private static String uid(com.fasterxml.jackson.databind.JsonNode data) {
        return data == null || data.path("uid").isMissingNode() ? null : data.path("uid").asText();
    }

    private com.fasterxml.jackson.databind.JsonNode post(Tenant t, String url, Object body) {
        try {
            MvcResult r = mockMvc.perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post(url)
                                    .header("Authorization", "Bearer " + t.adminToken)
                                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                    .content(json.writeValueAsString(body)))
                    .andReturn();
            String text = r.getResponse().getContentAsString(StandardCharsets.UTF_8);
            if (r.getResponse().getStatus() / 100 != 2) {
                System.out.println("[sweep] seed failed " + url + " -> " + r.getResponse().getStatus()
                        + " " + text.substring(0, Math.min(200, text.length())));
                return null;
            }
            return json.readTree(text).path("data");
        } catch (Exception ex) {
            System.out.println("[sweep] seed failed " + url + " -> " + ex);
            return null;
        } finally {
            RequestContext.clear();
        }
    }

    // ---------------------------------------------------------------------
    // Endpoint enumeration
    // ---------------------------------------------------------------------

    private record Endpoint(String pattern, HandlerMethod handler, List<String> pathVars) {
        String key() {
            return "GET " + pattern;
        }
    }

    /** Reason this endpoint is not exercised, or null when it is. */
    private String classify(Endpoint e) {
        if (!e.pattern().startsWith("/api/")) {
            return "not an /api endpoint (framework/error/docs mapping)";
        }
        String listed = SKIPPED.get(e.key());
        if (listed != null) {
            return listed;
        }
        for (String v : e.pathVars()) {
            if (!v.toLowerCase().endsWith("uid")) {
                return "path variable '" + v + "' is not a uid";
            }
        }
        return null;
    }

    private List<Endpoint> getEndpoints() {
        List<Endpoint> out = new ArrayList<>();
        for (Map.Entry<RequestMappingInfo, HandlerMethod> m : mappings.getHandlerMethods().entrySet()) {
            Set<org.springframework.web.bind.annotation.RequestMethod> verbs =
                    m.getKey().getMethodsCondition().getMethods();
            if (!verbs.contains(org.springframework.web.bind.annotation.RequestMethod.GET)) {
                continue;
            }
            Set<String> patterns = m.getKey().getPathPatternsCondition() == null
                    ? Set.of() : m.getKey().getPathPatternsCondition().getPatternValues();
            for (String p : new TreeSet<>(patterns)) {
                List<String> vars = new ArrayList<>();
                Matcher mt = PATH_VAR.matcher(p);
                while (mt.find()) {
                    vars.add(mt.group(1));
                }
                out.add(new Endpoint(p, m.getValue(), vars));
            }
        }
        out.sort((a, b) -> a.pattern().compareTo(b.pattern()));
        return out;
    }

    // ---------------------------------------------------------------------
    // Parameter filling
    // ---------------------------------------------------------------------

    /** A required query parameter the endpoint cannot be called without. */
    private record Param(String name, Class<?> type) {
    }

    private List<Param> requiredParams(HandlerMethod h) {
        List<Param> out = new ArrayList<>();
        for (MethodParameter p : h.getMethodParameters()) {
            RequestParam rp = p.getParameterAnnotation(RequestParam.class);
            if (rp == null || !rp.required()
                    || !ValueConstants.DEFAULT_NONE.equals(rp.defaultValue())
                    || java.util.Optional.class.equals(p.getParameterType())
                    || Map.class.isAssignableFrom(p.getParameterType())) {
                continue;
            }
            p.initParameterNameDiscovery(names);
            String name = !rp.name().isEmpty() ? rp.name()
                    : !rp.value().isEmpty() ? rp.value() : p.getParameterName();
            if (name == null) {
                continue;
            }
            out.add(new Param(name, p.getParameterType()));
        }
        return out;
    }

    /** Whether the param names a tenant boundary (and so is swapped for B's value when hostile). */
    private static boolean isTenantParam(String name) {
        String n = name.toLowerCase();
        return n.equals("companyid") || n.equals("companyuid") || n.equals("branchid")
                || n.equals("branchuid") || n.equals("organisationid")
                || n.equals("organisationuid") || n.equals("useruid") || n.equals("userid");
    }

    private String fill(Param p, Tenant t) {
        String n = p.name().toLowerCase();
        switch (n) {
            case "companyid": return String.valueOf(t.companyId);
            case "companyuid": return t.companyUid;
            case "branchid": return String.valueOf(t.branchId);
            case "branchuid": return t.branchUid;
            case "organisationid": return String.valueOf(t.orgId);
            case "organisationuid": return t.orgUid;
            case "useruid": return t.adminUid;
            default: break;
        }
        Class<?> type = p.type();
        if (type == LocalDate.class) {
            return n.startsWith("from") || n.startsWith("start") ? "2000-01-01" : "2100-01-01";
        }
        if (type == java.time.LocalDateTime.class) {
            return n.startsWith("from") || n.startsWith("start")
                    ? "2000-01-01T00:00:00" : "2100-01-01T00:00:00";
        }
        if (type == java.time.Instant.class || type == java.time.OffsetDateTime.class) {
            return n.startsWith("from") || n.startsWith("start")
                    ? "2000-01-01T00:00:00Z" : "2100-01-01T00:00:00Z";
        }
        if (type == java.time.YearMonth.class) {
            return "2026-01";
        }
        if (type.isEnum()) {
            return ((Enum<?>) type.getEnumConstants()[0]).name();
        }
        if (type == Boolean.class || type == boolean.class) {
            return "false";
        }
        // An id/uid param that names one of the tenant's own tables: use the tenant's own row.
        String base = n.endsWith("uid") ? n.substring(0, n.length() - 3)
                : n.endsWith("id") ? n.substring(0, n.length() - 2) : null;
        if (base != null && !base.isEmpty()) {
            String table = tableFor(base, t);
            if (table != null) {
                return n.endsWith("uid") ? t.uidsByTable.get(table).get(0)
                        : String.valueOf(t.firstIdByTable.getOrDefault(table, 1L));
            }
        }
        if (Number.class.isAssignableFrom(type) || type.isPrimitive()) {
            return "1";
        }
        if (n.endsWith("uid")) {
            return "01ARZ3NDEKTSV4RRFFQ69G5FAV";   // well-formed, belongs to nobody
        }
        return "x";
    }

    private static final Map<String, String> TABLE_ALIASES = Map.of(
            "account", "chart_of_accounts",
            "location", "stock_locations",
            "unit", "units_of_measure",
            "station", "prep_stations");

    private String tableFor(String base, Tenant t) {
        String snake = base.replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase();
        List<String> candidates = new ArrayList<>(List.of(snake, snake + "s", snake + "es",
                snake.replaceAll("y$", "ies")));
        if (TABLE_ALIASES.containsKey(snake)) {
            candidates.add(TABLE_ALIASES.get(snake));
        }
        for (String c : candidates) {
            if (t.uidsByTable.containsKey(c)) {
                return c;
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Calling
    // ---------------------------------------------------------------------

    private record Result(int status, String body) {
    }

    private Result call(Endpoint e, Map<String, String> pathValues, Map<String, String> params,
                        String token) {
        String url = e.pattern();
        for (var pv : pathValues.entrySet()) {
            url = url.replace("{" + pv.getKey() + "}", pv.getValue());
        }
        // Path patterns with regexes were stripped by the capture above; anything left is a bug.
        MockHttpServletRequestBuilder rb = get(url).header("Authorization", "Bearer " + token);
        params.forEach(rb::param);
        try {
            MvcResult r = mockMvc.perform(rb).andReturn();
            return new Result(r.getResponse().getStatus(),
                    r.getResponse().getContentAsString(StandardCharsets.UTF_8));
        } catch (Exception | AssertionError ex) {
            // A handler that throws past every advice is a robustness problem, not a leak.
            return new Result(599, "");
        } finally {
            RequestContext.clear();
        }
    }

    private static Set<String> tokens(String body) {
        Set<String> out = new HashSet<>();
        Matcher m = TOKEN.matcher(body);
        while (m.find()) {
            out.add(m.group());
        }
        return out;
    }

    private static List<String> intersect(String body, Set<String> uids) {
        List<String> hits = new ArrayList<>();
        for (String tok : tokens(body)) {
            if (uids.contains(tok)) {
                hits.add(tok);
            }
        }
        return hits;
    }

    private Map<String, String> paramsFor(Endpoint e, Tenant t, boolean hostile, Tenant own) {
        Map<String, String> out = new LinkedHashMap<>();
        for (Param p : requiredParams(e.handler())) {
            out.put(p.name(), fill(p, hostile && isTenantParam(p.name()) ? t : own));
        }
        return out;
    }

    private static String describe(Endpoint e, Map<String, String> params) {
        return e.key() + (params.isEmpty() ? "" : " " + params);
    }

    // ---------------------------------------------------------------------
    // The sweeps
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("harvest sanity · tenant B has real rows to leak")
    void harvestFoundRealRows() {
        assertThat(tenantB.uidsByTable.keySet())
                .as("tables with B rows — the provisioned tenant must hold a price list, "
                        + "walk-in customer, till and chart of accounts")
                .contains("organisations", "companies", "branches", "app_users");
        assertThat(tenantB.allUids.size()).isGreaterThan(50);
        assertThat(tenantB.allUids).doesNotContainAnyElementsOf(tenantA.allUids);
        assertThat(tenantB.uidsByTable.values().stream().flatMap(List::stream)
                .filter(u -> !TOKEN.matcher(u).matches()).toList())
                .as("every uid must be 26 alphanumerics, or the response scan would miss it")
                .isEmpty();
    }

    @Test
    @DisplayName("calibration · the scan is not vacuous: B's own admin DOES see B's uids")
    void calibrationScanSeesOwnUids() {
        int endpointsWithOwnUids = 0;
        for (Endpoint e : getEndpoints()) {
            if (classify(e) != null || !e.pathVars().isEmpty()) {
                continue;
            }
            Result r = call(e, Map.of(), paramsFor(e, tenantB, false, tenantB), tenantB.adminToken);
            if (r.status() / 100 == 2 && !intersect(r.body(), tenantB.allUids).isEmpty()) {
                endpointsWithOwnUids++;
            }
        }
        System.out.println("[sweep] calibration: B's admin saw B uids on " + endpointsWithOwnUids
                + " endpoints");
        assertThat(endpointsWithOwnUids)
                .as("if the owner of the data cannot see it through this harness, a pass for "
                        + "tenant A proves nothing")
                .isGreaterThan(40);
    }

    @Test
    @DisplayName("no-path-variable GETs · own params, then B's params — no B uid in the body")
    void listEndpointsNeverReturnAnotherTenantsUid() {
        long t0 = System.currentTimeMillis();
        List<String> leaks = new ArrayList<>();
        int own = 0;
        int hostile = 0;
        int ok = 0;
        int serverErrors = 0;
        for (Endpoint e : getEndpoints()) {
            if (classify(e) != null || !e.pathVars().isEmpty()) {
                continue;
            }
            Map<String, String> ownParams = paramsFor(e, tenantA, false, tenantA);
            Result r = call(e, Map.of(), ownParams, tenantA.adminToken);
            own++;
            ok += r.status() / 100 == 2 ? 1 : 0;
            serverErrors += r.status() >= 500 ? 1 : 0;
            List<String> hits = intersect(r.body(), tenantB.allUids);
            if (!hits.isEmpty()) {
                leaks.add("OWN " + r.status() + " " + describe(e, ownParams) + " -> " + hits);
            }

            Map<String, String> hostileParams = paramsFor(e, tenantB, true, tenantA);
            if (!hostileParams.equals(ownParams)) {
                Result h = call(e, Map.of(), hostileParams, tenantA.adminToken);
                hostile++;
                if (h.status() / 100 == 2) {
                    List<String> hh = intersect(h.body(), tenantB.allUids);
                    if (!hh.isEmpty()) {
                        leaks.add("HOSTILE " + h.status() + " " + describe(e, hostileParams)
                                + " -> " + hh);
                    }
                }
            }
        }
        System.out.println("[sweep] list: " + own + " own-scope calls (" + ok + " 2xx, "
                + serverErrors + " 5xx), " + hostile + " hostile-param calls, "
                + (System.currentTimeMillis() - t0) + " ms");
        assertThat(leaks).as("tenant-B uids reachable by tenant A's administrator").isEmpty();
    }

    @Test
    @DisplayName("uid-path GETs · one B uid per harvested table — refused or free of B uids")
    void uidEndpointsNeverServeAnotherTenantsRow() {
        long t0 = System.currentTimeMillis();
        List<Endpoint> eps = getEndpoints().stream()
                .filter(e -> classify(e) == null && !e.pathVars().isEmpty()).toList();
        // one representative uid per harvested table
        List<String> sample = new ArrayList<>();
        for (List<String> uids : tenantB.uidsByTable.values()) {
            sample.add(uids.get(0));
        }
        List<String> leaks = new ArrayList<>();
        Map<Integer, Integer> statuses = new TreeMap<>();
        int calls = 0;
        int twoXx = 0;
        for (Endpoint e : eps) {
            Map<String, String> params = paramsFor(e, tenantA, false, tenantA);
            for (String uid : sample) {
                Map<String, String> pv = new LinkedHashMap<>();
                e.pathVars().forEach(v -> pv.put(v, uid));
                Result r = call(e, pv, params, tenantA.adminToken);
                calls++;
                statuses.merge(r.status(), 1, Integer::sum);
                if (r.status() / 100 != 2) {
                    continue;
                }
                twoXx++;
                List<String> hits = intersect(r.body(), tenantB.allUids);
                if (!hits.isEmpty()) {
                    leaks.add(r.status() + " " + e.key() + " with " + uid + " -> " + hits);
                }
            }
        }
        System.out.println("[sweep] uid-path: " + eps.size() + " endpoints x " + sample.size()
                + " B uids = " + calls + " calls (" + twoXx + " 2xx), "
                + (System.currentTimeMillis() - t0) + " ms; statuses " + statuses);
        assertThat(leaks).as("a B uid served to tenant A with a 2xx").isEmpty();
    }

    @Test
    @DisplayName("calibration · the uid-path harness gets 2xx for a tenant's OWN rows")
    void calibrationUidPathServesOwnRows() {
        List<Endpoint> eps = getEndpoints().stream()
                .filter(e -> classify(e) == null && !e.pathVars().isEmpty()).toList();
        Set<String> servedTables = new TreeSet<>();
        Set<String> endpointsServed = new TreeSet<>();
        for (Endpoint e : eps) {
            Map<String, String> params = paramsFor(e, tenantB, false, tenantB);
            for (var table : tenantB.uidsByTable.entrySet()) {
                String uid = table.getValue().get(0);
                Map<String, String> pv = new LinkedHashMap<>();
                e.pathVars().forEach(v -> pv.put(v, uid));
                Result r = call(e, pv, params, tenantB.adminToken);
                if (r.status() / 100 == 2) {
                    servedTables.add(table.getKey());
                    endpointsServed.add(e.key());
                }
            }
        }
        System.out.println("[sweep] calibration uid-path: B's admin was served " + endpointsServed.size()
                + " endpoints / " + servedTables.size() + " tables with its own uid: " + servedTables);
        Set<String> never = new TreeSet<>();
        eps.forEach(e -> never.add(e.key()));
        never.removeAll(endpointsServed);
        System.out.println("[sweep] uid endpoints with no B row to serve (" + never.size() + "): " + never);
        assertThat(endpointsServed.size())
                .as("if the owner cannot read its own rows through these URLs, a refusal for tenant "
                        + "A proves nothing")
                .isGreaterThan(40);
    }

    @Test
    @DisplayName("coverage report · what is skipped and why")
    void coverageReport() {
        Map<String, List<String>> skipped = new TreeMap<>();
        int exercised = 0;
        for (Endpoint e : getEndpoints()) {
            String why = classify(e);
            if (why == null) {
                exercised++;
            } else {
                skipped.computeIfAbsent(why.replaceAll("'[^']*'", "'..'"), k -> new ArrayList<>())
                        .add(e.key());
            }
        }
        System.out.println("[sweep] GET endpoints exercised: " + exercised);
        skipped.forEach((why, list) -> System.out.println(
                "[sweep] skipped (" + list.size() + ") " + why + ": " + list));
        System.out.println("[sweep] B uids harvested: " + tenantB.allUids.size() + " across "
                + tenantB.uidsByTable.size() + " tables");
        assertThat(exercised).isGreaterThan(100);
    }
}
