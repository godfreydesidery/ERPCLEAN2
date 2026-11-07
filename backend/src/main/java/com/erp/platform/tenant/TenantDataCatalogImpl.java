package com.erp.platform.tenant;

import com.erp.platform.tenant.TenantDataPlan.CycleBreak;
import com.erp.platform.tenant.TenantTable.Kind;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Classifies every table in the live schema and orders the tenant ones for deletion.
 *
 * <h2>The three classes (MULTITENANCY-PLAN §1.1), and why each is handled as it is</h2>
 *
 * <ul>
 *   <li><b>Root</b> - {@code organisations}. One row, {@code id = :org}. Deleted last: it is
 *       referenced (RESTRICT) by {@code app_users}, {@code roles} and {@code companies}.
 *   <li><b>Directly scoped</b> - a table with {@code organisation_id} and/or {@code company_id}.
 *       A {@code company_id} is not trusted by itself: it must belong to one of the tenant's
 *       companies ({@code companies.organisation_id = :org}). Selection is by VALUE, not by FK,
 *       because ~19 line tables carry a denormalised {@code company_id} with no FK. A NULL id
 *       matches nothing, which is the point: {@code roles.organisation_id IS NULL} are the shipped
 *       global bundles and {@code audit_logs.company_id IS NULL} are unattributed events; neither
 *       is this tenant's to export or delete (invariant I-2).
 *   <li><b>Children</b> - a table with neither column, reachable by foreign key from a scoped table
 *       (invoice lines, junctions such as {@code user_branch}, {@code role_permission}). Their rows
 *       are selected by {@code fk IN (SELECT pk FROM parent WHERE <parent filter>)}. The closure
 *       is computed in rounds; a table classified in round N filters through parents classified
 *       before round N, which is what keeps the generated SQL finite. A table with several parents
 *       takes the UNION: a junction row blocks the delete of EITHER parent, so it must go with
 *       either. Any FK a round misses (a child of a later-round sibling) still cannot slip through
 *       a purge silently: the parent's DELETE would violate it and the whole transaction rolls
 *       back.
 *   <li><b>Global</b> - everything else: {@code permissions}, {@code currencies},
 *       {@code processed_events}, {@code flyway_schema_history}. Product-defined or infrastructure,
 *       one copy for all tenants; never exported, never deleted. A global table by definition
 *       references no tenant table (otherwise it would have been classified a child).
 * </ul>
 *
 * <h2>Deletion order</h2>
 *
 * Reverse topological over the FK graph restricted to tenant tables: a table is deleted once every
 * tenant table that references it is gone. A self-reference (chart_of_accounts.parent,
 * journal_entries.reversal_of, ...) is not an ordering edge: NO ACTION foreign keys are checked at
 * the end of the STATEMENT, so one {@code DELETE ... WHERE <tenant filter>} removing parent and
 * child together is valid. A RESTRICT self-reference is checked immediately, so it is reported as
 * a deletion blocker instead. A cycle between two or more tables cannot be ordered at all; the
 * planner breaks it by nulling a NULLABLE foreign key on a directly scoped table (whose filter does
 * not depend on that key) and reports the break; with no such key the plan carries a blocker and
 * purge refuses.
 *
 * <h2>Triggers and rules</h2>
 *
 * Every user trigger and rewrite rule on a tenant table is listed. One that fires on DELETE (or on
 * UPDATE of a cycle-break table) is a blocker: purge never disables triggers, even per session.
 */
@Service
class TenantDataCatalogImpl implements TenantDataCatalog {

    private static final Logger log = LoggerFactory.getLogger(TenantDataCatalogImpl.class);

    /** Bookkeeping owned by Flyway, not by any tenant. */
    private static final Set<String> INFRASTRUCTURE = Set.of("flyway_schema_history");

    /**
     * Credential columns. Anything ending {@code _hash} is treated as one (password, PIN and
     * refresh-token hashes) unless it is a content checksum; the rest are named secrets. A new
     * credential column is redacted by default, which is the safe direction; the integration test
     * also fails if a column that looks like a credential is NOT redacted.
     */
    private static final Pattern SECRET_NAME = Pattern.compile(
            "(^|_)(secret|client_secret|secret_key|api_key|private_key|access_token|refresh_token)$");
    private static final Set<String> HASH_COLUMNS_THAT_ARE_NOT_SECRETS = Set.of("content_hash");

    private final JdbcTemplate jdbc;

    TenantDataCatalogImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // -------------------------------------------------------------------------
    // Catalogue rows
    // -------------------------------------------------------------------------

    private record Fk(String name, String child, String parent, List<String> cols,
                      List<String> refCols, boolean restrict, boolean nullable) {
    }

    @Override
    public TenantDataPlan planFor(long organisationId) {
        String org = Long.toString(organisationId);

        Map<String, List<String>> columns = loadColumns();
        INFRASTRUCTURE.forEach(columns::remove);
        Map<String, List<String>> pks = loadPrimaryKeys();
        List<Fk> fks = loadForeignKeys().stream()
                .filter(f -> columns.containsKey(f.child) && columns.containsKey(f.parent))
                .toList();

        Map<String, TenantTable> tenant = new LinkedHashMap<>();

        // --- Root and directly scoped ------------------------------------------------------
        for (var e : new TreeMap<>(columns).entrySet()) {
            String table = e.getKey();
            List<String> cols = e.getValue();
            if (table.equals("organisations")) {
                tenant.put(table, table(table, Kind.ROOT, cols, pks, "\"id\" = " + org));
            } else if (table.equals("audit_logs")) {
                tenant.put(table, table(table, Kind.DIRECT, cols, pks, auditFilter(org)));
            } else if (cols.contains("organisation_id") || cols.contains("company_id")) {
                tenant.put(table, table(table, Kind.DIRECT, cols, pks, directFilter(cols, org)));
            }
        }

        // --- Children, in rounds ------------------------------------------------------------
        boolean grew = true;
        while (grew) {
            grew = false;
            Map<String, TenantTable> thisRound = new LinkedHashMap<>();
            for (var e : new TreeMap<>(columns).entrySet()) {
                String table = e.getKey();
                if (tenant.containsKey(table)) {
                    continue;
                }
                List<String> branches = new ArrayList<>();
                for (Fk fk : fks) {
                    if (fk.child.equals(table) && !fk.parent.equals(table)
                            && tenant.containsKey(fk.parent)) {
                        branches.add(viaParent(fk, tenant.get(fk.parent)));
                    }
                }
                if (!branches.isEmpty()) {
                    thisRound.put(table, table(table, Kind.CHILD, e.getValue(), pks,
                            "(" + String.join(" OR ", branches) + ")"));
                }
            }
            if (!thisRound.isEmpty()) {
                tenant.putAll(thisRound);
                grew = true;
            }
        }

        List<String> global = new ArrayList<>(new TreeSet<>(columns.keySet()));
        global.removeAll(tenant.keySet());

        // --- Order ---------------------------------------------------------------------------
        List<String> blockers = new ArrayList<>();
        List<CycleBreak> breaks = new ArrayList<>();
        List<String> order = deletionOrder(tenant, fks, breaks, blockers);

        // --- Triggers and rules --------------------------------------------------------------
        List<String> seen = new ArrayList<>();
        inspectHooks(tenant.keySet(), breaks, seen, blockers);

        List<TenantTable> ordered = new ArrayList<>();
        order.forEach(t -> ordered.add(tenant.get(t)));
        log.info("Tenant data plan for organisation {}: {} scoped/child tables, {} global, "
                        + "{} cycle break(s), {} blocker(s)",
                organisationId, ordered.size(), global.size(), breaks.size(), blockers.size());
        return new TenantDataPlan(organisationId, List.copyOf(ordered), List.copyOf(global),
                List.copyOf(breaks), List.copyOf(seen), List.copyOf(blockers));
    }

    // -------------------------------------------------------------------------
    // Filters
    // -------------------------------------------------------------------------

    private static String directFilter(List<String> cols, String org) {
        List<String> parts = new ArrayList<>();
        if (cols.contains("organisation_id")) {
            parts.add("\"organisation_id\" = " + org);
        }
        if (cols.contains("company_id")) {
            parts.add("\"company_id\" IN (SELECT \"id\" FROM \"companies\" "
                    + "WHERE \"organisation_id\" = " + org + ")");
        }
        return "(" + String.join(" OR ", parts) + ")";
    }

    /**
     * The audit trail is the one scoped table whose rows are also tied to a tenant THROUGH A USER:
     * login events carry neither a company nor an organisation (the tenant is not yet established)
     * but their {@code actor_user_id} is a tenant user, and that foreign key would block the user
     * delete. So the filter adds "acted by one of the tenant's users".
     *
     * <p>It EXCLUDES the platform operator's lifecycle rows about this organisation
     * ({@code target_type = 'organisations'}, e.g. ORG.CREATE / SUSPEND / EXPORT / PURGE): those are
     * the vendor's evidence that the departure rules were followed, they are attributed to the
     * operator, and they must survive the purge. The exclusion yields if the actor is a tenant
     * user, so it can never leave a row that blocks that user's delete.
     */
    private static String auditFilter(String org) {
        String tenantUsers = "(SELECT \"id\" FROM \"app_users\" WHERE \"organisation_id\" = " + org + ")";
        return "((\"organisation_id\" = " + org
                + " OR \"company_id\" IN (SELECT \"id\" FROM \"companies\" WHERE \"organisation_id\" = "
                + org + ")"
                + " OR \"actor_user_id\" IN " + tenantUsers + ")"
                + " AND NOT (\"target_type\" = 'organisations' AND \"target_id\" = " + org
                + " AND (\"actor_user_id\" IS NULL OR \"actor_user_id\" NOT IN " + tenantUsers + ")))";
    }

    private static String viaParent(Fk fk, TenantTable parent) {
        String child = fk.cols.stream().map(TenantDataCatalogImpl::q).reduce((a, b) -> a + ", " + b)
                .orElseThrow();
        String refs = fk.refCols.stream().map(TenantDataCatalogImpl::q).reduce((a, b) -> a + ", " + b)
                .orElseThrow();
        String lhs = fk.cols.size() > 1 ? "(" + child + ")" : child;
        return lhs + " IN (SELECT " + refs + " FROM " + q(parent.name()) + " WHERE "
                + parent.rowFilterSql() + ")";
    }

    private TenantTable table(String name, Kind kind, List<String> cols, Map<String, List<String>> pks,
                              String filter) {
        List<String> redacted = cols.stream().filter(TenantDataCatalogImpl::isSecret).toList();
        List<String> exported = cols.stream().filter(c -> !redacted.contains(c)).toList();
        return new TenantTable(name, kind, List.copyOf(cols), exported, redacted,
                pks.getOrDefault(name, List.of()), filter);
    }

    static boolean isSecret(String column) {
        if (HASH_COLUMNS_THAT_ARE_NOT_SECRETS.contains(column)) {
            return false;
        }
        return column.endsWith("_hash") || SECRET_NAME.matcher(column).find();
    }

    static String q(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    // -------------------------------------------------------------------------
    // Ordering
    // -------------------------------------------------------------------------

    private List<String> deletionOrder(Map<String, TenantTable> tenant, List<Fk> fks,
                                       List<CycleBreak> breaks, List<String> blockers) {
        // Edges child -> parent among tenant tables. Self-references are not ordering edges.
        List<Fk> edges = new ArrayList<>();
        for (Fk fk : fks) {
            if (!tenant.containsKey(fk.child) || !tenant.containsKey(fk.parent)) {
                continue;
            }
            if (fk.child.equals(fk.parent)) {
                if (fk.restrict) {
                    blockers.add("Self-referencing RESTRICT key " + fk.name + " on " + fk.child
                            + " is checked per row, so no single DELETE can satisfy it.");
                }
                continue;
            }
            edges.add(fk);
        }

        Set<String> remaining = new TreeSet<>(tenant.keySet());
        List<String> order = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<String> ready = new ArrayList<>();
            for (String t : remaining) {
                final String table = t;
                boolean referenced = edges.stream()
                        .anyMatch(e -> e.parent.equals(table) && remaining.contains(e.child));
                if (!referenced) {
                    ready.add(t);
                }
            }
            if (!ready.isEmpty()) {
                order.addAll(ready);          // all independent of each other, alphabetical
                ready.forEach(remaining::remove);
                continue;
            }

            // Stuck: every remaining table is referenced by another remaining table, so there is a
            // cycle. Break an edge that lies INSIDE a strongly connected component - an edge merely
            // upstream of the cycle would be nulled for nothing.
            Map<String, Integer> component = components(remaining, edges);
            Fk breakAt = edges.stream()
                    .filter(e -> remaining.contains(e.child) && remaining.contains(e.parent))
                    .filter(e -> component.get(e.child).equals(component.get(e.parent)))
                    .filter(e -> e.nullable && tenant.get(e.child).kind() != Kind.CHILD)
                    .min(Comparator.comparing((Fk e) -> e.child).thenComparing(e -> e.name))
                    .orElse(null);
            if (breakAt == null) {
                blockers.add("Foreign-key cycle among " + remaining
                        + " has no nullable key on a directly scoped table to break it.");
                order.addAll(remaining);      // keep the report complete; purge will refuse
                break;
            }
            breaks.add(new CycleBreak(breakAt.child, breakAt.cols));
            edges.remove(breakAt);
        }
        return order;
    }

    /** Tarjan's strongly connected components over child -> parent edges among {@code nodes}. */
    private static Map<String, Integer> components(Set<String> nodes, List<Fk> edges) {
        Map<String, List<String>> out = new HashMap<>();
        for (Fk e : edges) {
            if (nodes.contains(e.child) && nodes.contains(e.parent)) {
                out.computeIfAbsent(e.child, k -> new ArrayList<>()).add(e.parent);
            }
        }
        Map<String, Integer> index = new HashMap<>();
        Map<String, Integer> low = new HashMap<>();
        Map<String, Integer> comp = new HashMap<>();
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        Set<String> onStack = new java.util.HashSet<>();
        int[] counter = {0, 0};   // [next index, next component]
        for (String n : nodes) {
            if (!index.containsKey(n)) {
                strongConnect(n, out, index, low, comp, stack, onStack, counter);
            }
        }
        return comp;
    }

    private static void strongConnect(String v, Map<String, List<String>> out,
                                      Map<String, Integer> index, Map<String, Integer> low,
                                      Map<String, Integer> comp, java.util.ArrayDeque<String> stack,
                                      Set<String> onStack, int[] counter) {
        index.put(v, counter[0]);
        low.put(v, counter[0]);
        counter[0]++;
        stack.push(v);
        onStack.add(v);
        for (String w : out.getOrDefault(v, List.of())) {
            if (!index.containsKey(w)) {
                strongConnect(w, out, index, low, comp, stack, onStack, counter);
                low.put(v, Math.min(low.get(v), low.get(w)));
            } else if (onStack.contains(w)) {
                low.put(v, Math.min(low.get(v), index.get(w)));
            }
        }
        if (low.get(v).equals(index.get(v))) {
            String w;
            do {
                w = stack.pop();
                onStack.remove(w);
                comp.put(w, counter[1]);
            } while (!w.equals(v));
            counter[1]++;
        }
    }

    // -------------------------------------------------------------------------
    // Triggers / rules
    // -------------------------------------------------------------------------

    private static final int TG_INSERT = 4;
    private static final int TG_DELETE = 8;
    private static final int TG_UPDATE = 16;

    private void inspectHooks(Set<String> tenantTables, List<CycleBreak> breaks, List<String> seen,
                              List<String> blockers) {
        Set<String> updated = new TreeSet<>();
        breaks.forEach(b -> updated.add(b.table()));

        jdbc.query("""
                SELECT c.relname, t.tgname, t.tgtype, t.tgenabled::text
                FROM pg_trigger t
                JOIN pg_class c ON c.oid = t.tgrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE NOT t.tgisinternal AND n.nspname = current_schema()
                ORDER BY c.relname, t.tgname
                """, rs -> {
            String table = rs.getString(1);
            if (!tenantTables.contains(table)) {
                return;
            }
            int type = rs.getInt(3);
            boolean enabled = !"D".equals(rs.getString(4));
            String events = (((type & TG_INSERT) != 0) ? "INSERT " : "")
                    + (((type & TG_UPDATE) != 0) ? "UPDATE " : "")
                    + (((type & TG_DELETE) != 0) ? "DELETE" : "");
            seen.add("trigger " + rs.getString(2) + " on " + table + " [" + events.trim() + "]"
                    + (enabled ? "" : " (disabled)"));
            if (enabled && ((type & TG_DELETE) != 0
                    || ((type & TG_UPDATE) != 0 && updated.contains(table)))) {
                blockers.add("Trigger " + rs.getString(2) + " on " + table
                        + " fires on a statement purge must run.");
            }
        });

        jdbc.query("""
                SELECT c.relname, r.rulename, r.ev_type::text
                FROM pg_rewrite r
                JOIN pg_class c ON c.oid = r.ev_class
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE r.rulename <> '_RETURN' AND n.nspname = current_schema()
                """, rs -> {
            String table = rs.getString(1);
            if (!tenantTables.contains(table)) {
                return;
            }
            String ev = rs.getString(3);
            seen.add("rule " + rs.getString(2) + " on " + table + " [ev_type " + ev + "]");
            if ("4".equals(ev) || ("2".equals(ev) && updated.contains(table))) {
                blockers.add("Rule " + rs.getString(2) + " on " + table
                        + " rewrites a statement purge must run.");
            }
        });
    }

    // -------------------------------------------------------------------------
    // Catalogue loading
    // -------------------------------------------------------------------------

    private Map<String, List<String>> loadColumns() {
        Map<String, List<String>> out = new HashMap<>();
        jdbc.query("""
                SELECT c.relname, a.attname
                FROM pg_attribute a
                JOIN pg_class c ON c.oid = a.attrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind IN ('r', 'p') AND NOT c.relispartition
                  AND a.attnum > 0 AND NOT a.attisdropped
                  AND n.nspname = current_schema()
                ORDER BY c.relname, a.attnum
                """, rs -> {
            out.computeIfAbsent(rs.getString(1), k -> new ArrayList<>()).add(rs.getString(2));
        });
        return out;
    }

    private Map<String, List<String>> loadPrimaryKeys() {
        Map<String, List<String>> out = new HashMap<>();
        jdbc.query("""
                SELECT c.relname,
                       (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                        FROM unnest(i.indkey::int2[]) WITH ORDINALITY k(attnum, ord)
                        JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum)
                FROM pg_index i
                JOIN pg_class c ON c.oid = i.indrelid
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE i.indisprimary AND n.nspname = current_schema()
                """, rs -> {
            out.put(rs.getString(1), Arrays.asList(rs.getString(2).split(",")));
        });
        return out;
    }

    private List<Fk> loadForeignKeys() {
        return jdbc.query("""
                SELECT con.conname, rel.relname, frel.relname, con.confdeltype::text,
                       (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                        FROM unnest(con.conkey) WITH ORDINALITY k(attnum, ord)
                        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum),
                       (SELECT string_agg(a.attname, ',' ORDER BY k.ord)
                        FROM unnest(con.confkey) WITH ORDINALITY k(attnum, ord)
                        JOIN pg_attribute a ON a.attrelid = con.confrelid AND a.attnum = k.attnum),
                       (SELECT bool_and(NOT a.attnotnull)
                        FROM unnest(con.conkey) k(attnum)
                        JOIN pg_attribute a ON a.attrelid = con.conrelid AND a.attnum = k.attnum)
                FROM pg_constraint con
                JOIN pg_class rel ON rel.oid = con.conrelid
                JOIN pg_class frel ON frel.oid = con.confrelid
                JOIN pg_namespace n ON n.oid = rel.relnamespace
                WHERE con.contype = 'f' AND n.nspname = current_schema()
                ORDER BY rel.relname, con.conname
                """, (rs, i) -> new Fk(rs.getString(1), rs.getString(2), rs.getString(3),
                Arrays.asList(rs.getString(5).split(",")),
                Arrays.asList(rs.getString(6).split(",")),
                "r".equals(rs.getString(4)), rs.getBoolean(7)));
    }
}
