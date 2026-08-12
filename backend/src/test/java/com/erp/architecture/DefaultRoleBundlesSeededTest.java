package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/**
 * Guards the default operational role bundles seeded by {@code R__seed_permissions.sql} (ADR-0057).
 * Static file parse (no DB, no Docker) — sibling to {@link PermissionCodesSeededTest}. Verifies:
 * <ul>
 *   <li>all 12 shipped operational roles have a {@code roles} row (is_system, org-wide);</li>
 *   <li>each role holds at least one grant (no empty bundle);</li>
 *   <li>every granted permission code EXISTS in the catalogue (no phantom grant — a typo'd or
 *       removed code would silently grant nothing at runtime, invisible to RBAC);</li>
 *   <li>signature least-privilege anchors are present (catches an accidental grant removal that
 *       would break a persona's core job — e.g. a Cashier that can no longer ring a sale).</li>
 * </ul>
 * This complements the fresh-DB boot check: it fails fast at unit-test time if a future edit drops a
 * role, mistypes a code, or guts a bundle.
 */
class DefaultRoleBundlesSeededTest {

    private static final String SEED_RESOURCE = "/db/migration/R__seed_permissions.sql";

    /** Marker separating the permission catalogue (+ ORG_ADMIN grant) from the role-bundle section. */
    private static final String BUNDLE_MARKER = "Default operational role bundles";

    /** Any single-quoted dotted-uppercase permission code, e.g. 'SALES.INVOICE.VIEW'. */
    private static final Pattern PERM_CODE =
            Pattern.compile("'([A-Z][A-Z0-9_]*(?:\\.[A-Z0-9_]+)+)'");

    /** A grant pair line: ('ROLE_CODE','PERM.CODE') — role code is dot-free, perm code is dotted. */
    private static final Pattern GRANT_PAIR =
            Pattern.compile("\\('([A-Z][A-Z0-9_]*)','([A-Z][A-Z0-9_]*(?:\\.[A-Z0-9_]+)+)'\\)");

    /** The 12 shipped roles (ADR-0057 §2). */
    private static final List<String> EXPECTED_ROLES = List.of(
            "SALESPERSON", "CASHIER", "FIELD_SALES_AGENT", "STOREKEEPER", "ACCOUNTANT",
            "SALES_MANAGER", "BRANCH_MANAGER", "PROCUREMENT_OFFICER", "PROCUREMENT_MANAGER",
            "HR_PAYROLL_MANAGER", "FINANCE_DIRECTOR", "PRODUCTION_MANAGER");

    /** One signature capability per role — its removal would break the persona's core job. */
    private static final Map<String, String> ANCHORS = Map.ofEntries(
            Map.entry("SALESPERSON", "SALES.INVOICE.CREATE"),
            Map.entry("CASHIER", "POS.SALE.CREATE"),
            Map.entry("FIELD_SALES_AGENT", "STOCK.VAN_RECON.MANAGE"),
            // Receiving goods is the storekeeper's signature act. This anchor was STOCK.COUNT.POST
            // until UAT 2026-08-12 showed that permission also books the GL variance journal, so a
            // counter could approve their own shrinkage; posting moved to BRANCH_MANAGER and the
            // storekeeper keeps STOCK.COUNT.CREATE/VIEW.
            Map.entry("STOREKEEPER", "PURCHASE.RECEIVE"),
            Map.entry("ACCOUNTANT", "GL.POST"),
            Map.entry("SALES_MANAGER", "SALES.INVOICE.VOID"),
            Map.entry("BRANCH_MANAGER", "APPROVALS.DECIDE"),
            Map.entry("PROCUREMENT_OFFICER", "PURCHASE.ORDER.CREATE"),
            Map.entry("PROCUREMENT_MANAGER", "PURCHASE.ORDER.APPROVE"),
            Map.entry("HR_PAYROLL_MANAGER", "HR.PAYROLL.RUN"),
            Map.entry("FINANCE_DIRECTOR", "GL.YEAR.CLOSE"),
            Map.entry("PRODUCTION_MANAGER", "WORKORDER.RELEASE"));

    @Test
    void allDefaultRoleBundlesAreSeededConsistently() throws IOException {
        String sql = loadSeed();
        int split = sql.indexOf(BUNDLE_MARKER);
        assertThat(split)
                .as("R__seed_permissions.sql must contain the '%s' section (ADR-0057)", BUNDLE_MARKER)
                .isGreaterThan(0);

        String cataloguePart = sql.substring(0, split);
        String bundlePart = sql.substring(split);

        // Catalogue = every permission code DEFINED before the bundle section (ORG_ADMIN grants via
        // CROSS JOIN, so no literal codes appear there) — the authoritative set of real codes.
        Set<String> catalogue = codesIn(cataloguePart);

        // Grants declared in the bundle section, grouped by role.
        Map<String, Set<String>> grantsByRole = new LinkedHashMap<>();
        Matcher gm = GRANT_PAIR.matcher(bundlePart);
        while (gm.find()) {
            grantsByRole.computeIfAbsent(gm.group(1), k -> new LinkedHashSet<>()).add(gm.group(2));
        }

        // 1) every expected role has a roles-INSERT row (format: 'uid', 'CODE', 'Name', ...).
        Set<String> missingRoleRows = new TreeSet<>();
        for (String role : EXPECTED_ROLES) {
            if (!bundlePart.contains("', '" + role + "', '")) {
                missingRoleRows.add(role);
            }
        }
        assertThat(missingRoleRows)
                .as("Default roles missing their `roles` INSERT row in the seed")
                .isEmpty();

        // 2) every expected role holds at least one grant.
        Set<String> emptyBundles = new TreeSet<>(EXPECTED_ROLES);
        emptyBundles.removeAll(grantsByRole.keySet());
        assertThat(emptyBundles)
                .as("Default roles with no permission grants (empty bundle)")
                .isEmpty();

        // 3) no phantom grant — every granted code must exist in the catalogue.
        Map<String, Set<String>> phantom = new LinkedHashMap<>();
        for (var e : grantsByRole.entrySet()) {
            for (String code : e.getValue()) {
                if (!catalogue.contains(code)) {
                    phantom.computeIfAbsent(e.getKey(), k -> new TreeSet<>()).add(code);
                }
            }
        }
        assertThat(phantom)
                .as("Role bundles grant permission codes NOT defined in the catalogue "
                        + "(a phantom grant silently grants nothing at runtime). Role → bad codes: %s",
                        phantom)
                .isEmpty();

        // 4) signature least-privilege anchors are still present.
        Map<String, String> missingAnchors = new LinkedHashMap<>();
        for (var e : ANCHORS.entrySet()) {
            Set<String> held = grantsByRole.getOrDefault(e.getKey(), Set.of());
            if (!held.contains(e.getValue())) {
                missingAnchors.put(e.getKey(), e.getValue());
            }
        }
        assertThat(missingAnchors)
                .as("Default roles missing a signature capability (would break the persona's core "
                        + "job). Role → missing anchor: %s", missingAnchors)
                .isEmpty();

        // 5) ADR-0059 seed fence: no operational bundle may carry a reserved "power-to-delegate"
        // permission. A reserved code in a least-privilege bundle would silently arm vertical
        // privilege escalation for every holder (they could self-elevate to ORG_ADMIN). Only
        // ORG_ADMIN (absorbing the whole catalogue via CROSS JOIN) legitimately holds these.
        Set<String> reserved = Set.of(
                "USER.MANAGE", "USER.COMPANY.MANAGE", "ROLE.MANAGE", "ROLE.ADMIN", "BRANCH.ASSIGN");
        Map<String, Set<String>> leaked = new LinkedHashMap<>();
        for (var e : grantsByRole.entrySet()) {
            Set<String> bad = new TreeSet<>(e.getValue());
            bad.retainAll(reserved);
            if (!bad.isEmpty()) {
                leaked.put(e.getKey(), bad);
            }
        }
        assertThat(leaked)
                .as("Operational bundles must not carry reserved escalation permissions (ADR-0059). "
                        + "Role → reserved codes: %s", leaked)
                .isEmpty();
    }

    private static Set<String> codesIn(String text) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher m = PERM_CODE.matcher(text);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }

    private static String loadSeed() throws IOException {
        try (InputStream is = DefaultRoleBundlesSeededTest.class.getResourceAsStream(SEED_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException("Cannot find classpath resource " + SEED_RESOURCE);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
