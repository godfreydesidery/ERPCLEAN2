package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * ADR-0047 guard: asserts the screen-read-closure manifest is honest against live controller
 * {@code @PreAuthorize} annotations and that every code the manifest names is seeded.
 *
 * <p>Three checks, all no-DB (classpath scan + JSON parse + seed-file read):
 * <ol>
 *   <li><b>(a) Gate honesty</b> — the actual gate-form + code on the controller's list handler
 *       matches what the manifest declares. Drift in either direction fails the build.</li>
 *   <li><b>(b) Required-read closure seeded</b> — every {@code required} read whose gate is
 *       closure-bearing ({@code has} or {@code scoped}) has its code present in
 *       {@code R__seed_permissions.sql}. Member-floor ({@code hasOrMember}/{@code scopedOrMember})
 *       and disjunction reads are skipped (membership or adjacent verb covers them).</li>
 *   <li><b>(c) No phantom</b> — every code/altCode the manifest names resolves in the seed.</li>
 * </ol>
 *
 * <p>Reuses {@link PermissionCodesSeededTest}'s scanner, PERM_CODE_PATTERN, and
 * {@code loadSeededCodes()} (same seed resource, no second implementation).
 *
 * <p>Manifest: {@code backend/src/test/resources/security/screen-read-closure.json}.
 */
class RolePermissionClosureTest {

    private static final String MANIFEST_RESOURCE = "/security/screen-read-closure.json";
    private static final String SEED_RESOURCE     = "/db/migration/R__seed_permissions.sql";

    /** Matches permission codes: uppercase-letter-prefixed, dot-separated segments. */
    private static final Pattern PERM_CODE_PATTERN =
            Pattern.compile("'([A-Z][A-Z0-9_]*(?:\\.[A-Z0-9_]+)+)'");

    /** Controller package to scan. */
    private static final String API_PACKAGE = "com.erp.api";

    // -------------------------------------------------------------------------

    @Test
    void gateHonestyRequiredClosureSeededAndNoPhantom() throws IOException {
        final Set<String> seededCodes = loadSeededCodes();
        final JsonNode manifest       = loadManifest();
        final JsonNode screens        = manifest.path("screens");

        final List<String> failures = new ArrayList<>();

        // Build a controller name → Class<?> map from classpath scan
        java.util.Map<String, Class<?>> controllerMap = buildControllerMap();

        for (JsonNode screenNode : screens) {
            final String screen = screenNode.path("screen").asText();
            final JsonNode reads = screenNode.path("reads");

            for (JsonNode readNode : reads) {
                final String controllerName = readNode.path("controller").asText();
                final String manifestCode   = readNode.path("code").asText();
                final String manifestGate   = readNode.path("gate").asText();
                final String necessity      = readNode.path("necessity").asText();
                final String altCode        = readNode.has("altCode")
                        ? readNode.path("altCode").asText() : null;

                final String ctx = "[screen=" + screen + ", controller=" + controllerName
                        + ", code=" + manifestCode + "]";

                // ── (c) No phantom — code must be in seed ──────────────────────────
                if (!seededCodes.contains(manifestCode)) {
                    failures.add("(c) PHANTOM code not in seed " + ctx);
                }
                if (altCode != null && !seededCodes.contains(altCode)) {
                    failures.add("(c) PHANTOM altCode '" + altCode + "' not in seed " + ctx);
                }

                // ── (a) Gate honesty — locate the controller and parse its gate ───
                Class<?> controller = controllerMap.get(controllerName);
                if (controller == null) {
                    failures.add("(a) Controller not found in " + API_PACKAGE + ": "
                            + controllerName + " " + ctx);
                    continue;
                }

                Method listHandler = findListHandler(controller);
                if (listHandler == null) {
                    failures.add("(a) No list @GetMapping handler (no path variable) found in "
                            + controllerName + " " + ctx);
                    continue;
                }

                PreAuthorize pa = listHandler.getAnnotation(PreAuthorize.class);
                if (pa == null) {
                    failures.add("(a) List handler " + controllerName + "#" + listHandler.getName()
                            + " has no @PreAuthorize " + ctx);
                    continue;
                }

                final String spel = pa.value();
                ParsedGate parsed = parseGate(spel);

                if (parsed == null) {
                    failures.add("(a) Could not parse gate form from SpEL '" + spel + "' " + ctx);
                    continue;
                }

                if (!parsed.gate.equals(manifestGate)) {
                    failures.add("(a) Gate mismatch " + ctx
                            + " — manifest says '" + manifestGate
                            + "' but controller has '" + parsed.gate + "'");
                }
                if (!parsed.code.equals(manifestCode)) {
                    failures.add("(a) Code mismatch " + ctx
                            + " — manifest says '" + manifestCode
                            + "' but controller has '" + parsed.code + "'");
                }
                if ("disjunction".equals(manifestGate)) {
                    if (altCode == null) {
                        failures.add("(a) Manifest gate is 'disjunction' but altCode missing " + ctx);
                    } else if (parsed.altCode == null) {
                        failures.add("(a) Controller SpEL is disjunction but altCode not parsed "
                                + ctx + " SpEL='" + spel + "'");
                    } else if (!parsed.altCode.equals(altCode)) {
                        failures.add("(a) AltCode mismatch " + ctx
                                + " — manifest says '" + altCode
                                + "' but controller has '" + parsed.altCode + "'");
                    }
                }

                // ── (b) Required closure-bearing reads must be seeded ─────────────
                // Applies only to required reads with gate = has | scoped
                if ("required".equals(necessity)
                        && ("has".equals(manifestGate) || "scoped".equals(manifestGate))) {
                    if (!seededCodes.contains(manifestCode)) {
                        // (c) already captured this; record the closure-bearing angle too
                        failures.add("(b) Required closure-bearing code '" + manifestCode
                                + "' is not seeded — role composition cannot satisfy this read "
                                + ctx);
                    }
                }
            }
        }

        assertThat(failures)
                .as("RolePermissionClosureTest failures (ADR-0047):\n%s",
                        String.join("\n", failures))
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Builds a simple name → Class map for all @RestController classes in the API package.
     * Uses the same ClassPathScanningCandidateComponentProvider as PermissionCodesSeededTest.
     */
    private static java.util.Map<String, Class<?>> buildControllerMap() {
        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        java.util.Map<String, Class<?>> map = new java.util.LinkedHashMap<>();
        for (var bean : scanner.findCandidateComponents(API_PACKAGE)) {
            Class<?> cls = loadClass(bean.getBeanClassName());
            map.put(cls.getSimpleName(), cls);
        }
        return map;
    }

    /**
     * Finds the "list" handler: a {@code @GetMapping} method with no path-variable segment in its
     * mapped path (i.e. it maps to the controller root, not to e.g. {@code /uid/{uid}}).
     * Among all qualifying candidates, prefers the one with the shortest mapped path (root = "").
     */
    private static Method findListHandler(Class<?> controller) {
        Method best = null;
        int bestLen = Integer.MAX_VALUE;
        for (Method m : controller.getDeclaredMethods()) {
            GetMapping gm = m.getAnnotation(GetMapping.class);
            if (gm == null) {
                continue;
            }
            // Collect all mapped paths (value() and path() are aliases)
            String[] paths = gm.value().length > 0 ? gm.value() : gm.path();
            if (paths.length == 0) {
                // No path attribute — maps to controller root
                if (best == null) {
                    best = m;
                    bestLen = 0;
                }
                continue;
            }
            for (String p : paths) {
                if (!p.contains("{")) {
                    // Non-parameterised sub-path — still qualifies (e.g. /on-hand)
                    int len = p.length();
                    if (len < bestLen) {
                        bestLen = len;
                        best = m;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Parses a Spring Security SpEL string into a {@link ParsedGate}.
     *
     * <p>Supported forms (matching what {@code @perm} methods produce):
     * <ul>
     *   <li>{@code @perm.hasOrMember('CODE')} → gate={@code hasOrMember}</li>
     *   <li>{@code @perm.scopedOrMember(...,'type','CODE')} → gate={@code scopedOrMember}</li>
     *   <li>{@code @perm.has('CODE1') or @perm.has('CODE2')} → gate={@code disjunction}</li>
     *   <li>{@code @perm.has('CODE')} → gate={@code has}</li>
     *   <li>{@code @perm.scoped(...,'type','CODE')} → gate={@code scoped}</li>
     * </ul>
     */
    static ParsedGate parseGate(String spel) {
        if (spel == null || spel.isBlank()) {
            return null;
        }
        // hasOrMember takes precedence — check before plain 'has'
        if (spel.contains("@perm.hasOrMember(")) {
            String code = firstCode(spel);
            return code != null ? new ParsedGate("hasOrMember", code, null) : null;
        }
        // scopedOrMember — last quoted arg is the code
        if (spel.contains("@perm.scopedOrMember(")) {
            String code = lastCode(spel);
            return code != null ? new ParsedGate("scopedOrMember", code, null) : null;
        }
        // disjunction: two @perm.has(...) joined by " or "
        if (spel.contains(" or ") && spel.contains("@perm.has(")) {
            List<String> codes = allCodes(spel);
            if (codes.size() >= 2) {
                return new ParsedGate("disjunction", codes.get(0), codes.get(1));
            }
            return null;
        }
        // Plain has (single, no 'or')
        if (spel.contains("@perm.has(") && !spel.contains("@perm.scoped(")) {
            String code = firstCode(spel);
            return code != null ? new ParsedGate("has", code, null) : null;
        }
        // scoped — last quoted uppercase arg is the code
        if (spel.contains("@perm.scoped(")) {
            String code = lastCode(spel);
            return code != null ? new ParsedGate("scoped", code, null) : null;
        }
        return null;
    }

    /** Extracts the first PERM_CODE_PATTERN match from spel. */
    private static String firstCode(String spel) {
        Matcher m = PERM_CODE_PATTERN.matcher(spel);
        return m.find() ? m.group(1) : null;
    }

    /** Extracts the last PERM_CODE_PATTERN match from spel. */
    private static String lastCode(String spel) {
        Matcher m = PERM_CODE_PATTERN.matcher(spel);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return last;
    }

    /** Extracts all PERM_CODE_PATTERN matches from spel, in order. */
    private static List<String> allCodes(String spel) {
        List<String> codes = new ArrayList<>();
        Matcher m = PERM_CODE_PATTERN.matcher(spel);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }

    /** Reads R__seed_permissions.sql and extracts all permission codes (same as PermissionCodesSeededTest). */
    static Set<String> loadSeededCodes() throws IOException {
        try (InputStream is =
                RolePermissionClosureTest.class.getResourceAsStream(SEED_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Cannot find classpath resource " + SEED_RESOURCE
                                + " — check that backend/src/main/resources is on the test classpath.");
            }
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            java.util.Set<String> codes = new java.util.LinkedHashSet<>();
            Matcher m = PERM_CODE_PATTERN.matcher(sql);
            while (m.find()) {
                codes.add(m.group(1));
            }
            return codes;
        }
    }

    private static JsonNode loadManifest() throws IOException {
        try (InputStream is =
                RolePermissionClosureTest.class.getResourceAsStream(MANIFEST_RESOURCE)) {
            if (is == null) {
                fail("Cannot find manifest " + MANIFEST_RESOURCE
                        + " — expected at backend/src/test/resources/security/screen-read-closure.json");
            }
            return new ObjectMapper().readTree(is);
        }
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not load controller class " + name, e);
        }
    }

    // -------------------------------------------------------------------------

    /** Parsed gate form from a @PreAuthorize SpEL string. */
    record ParsedGate(String gate, String code, String altCode) {}
}
