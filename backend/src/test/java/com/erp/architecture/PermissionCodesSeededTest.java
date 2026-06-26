package com.erp.architecture;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.RestController;

/**
 * Guards against "phantom permission codes" — a @PreAuthorize referencing a code that is not
 * defined in R__seed_permissions.sql. EndpointAuthorizationTest only verifies a gate EXISTS; this
 * test verifies the code the gate names is actually seeded.
 *
 * <p>Scanning approach mirrors EndpointAuthorizationTest: classpath-scan com.erp.api for
 * @RestController classes, read every @PreAuthorize SpEL string, extract uppercase-dotted tokens,
 * then cross-check against every uppercase-dotted token found in the seed file.
 *
 * <p>Two SpEL patterns in use:
 * <ul>
 *   <li>{@code @perm.has('CODE')} — single quoted arg is the code.</li>
 *   <li>{@code @perm.scoped(expr,'entitytype','CODE')} — the third quoted arg is the code;
 *       the second is a lowercase entity-type string, intentionally excluded by the regex.</li>
 * </ul>
 */
class PermissionCodesSeededTest {

    /**
     * Matches a permission code: starts with an uppercase letter, followed by uppercase letters,
     * digits, underscores; contains at least one dot separator; each segment is uppercase+digits.
     * Examples: SALES.INVOICE.VIEW, USER.COMPANY.MANAGE, AR.RECEIPT.RECORD.
     *
     * <p>A lowercase token like 'invoice' or 'company' (entity-type args in perm.scoped) does NOT
     * match — it starts lowercase, so it is naturally excluded.
     */
    private static final Pattern PERM_CODE_PATTERN =
            Pattern.compile("'([A-Z][A-Z0-9_]*(?:\\.[A-Z0-9_]+)+)'");

    private static final String SEED_RESOURCE = "/db/migration/R__seed_permissions.sql";

    // -------------------------------------------------------------------------

    @Test
    void allPreAuthorizeCodesAreDefinedInSeed() throws IOException {
        Set<String> seededCodes = loadSeededCodes();

        // Map from permission code → list of "ControllerSimpleName#method" references
        Map<String, List<String>> phantoms = new LinkedHashMap<>();

        ClassPathScanningCandidateComponentProvider scanner =
                new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        for (var bean : scanner.findCandidateComponents("com.erp.api")) {
            Class<?> controller = loadClass(bean.getBeanClassName());
            for (Method method : controller.getDeclaredMethods()) {
                PreAuthorize pa = method.getAnnotation(PreAuthorize.class);
                if (pa == null) {
                    continue;
                }
                String spel = pa.value();
                String handlerId = controller.getSimpleName() + "#" + method.getName();
                Matcher m = PERM_CODE_PATTERN.matcher(spel);
                while (m.find()) {
                    String code = m.group(1);
                    if (!seededCodes.contains(code)) {
                        phantoms.computeIfAbsent(code, k -> new ArrayList<>()).add(handlerId);
                    }
                }
            }
        }

        assertThat(phantoms)
                .as(
                        "Permission codes referenced in @PreAuthorize but absent from %s.\n"
                                + "Fix: add the code to R__seed_permissions.sql.\n"
                                + "Offending codes → handlers: %s",
                        SEED_RESOURCE,
                        formatPhantoms(phantoms))
                .isEmpty();
    }

    // -------------------------------------------------------------------------

    /** Read R__seed_permissions.sql from the classpath and extract all uppercase-dotted tokens. */
    private static Set<String> loadSeededCodes() throws IOException {
        try (InputStream is =
                PermissionCodesSeededTest.class.getResourceAsStream(SEED_RESOURCE)) {
            if (is == null) {
                throw new IllegalStateException(
                        "Cannot find classpath resource " + SEED_RESOURCE
                                + " — check that backend/src/main/resources is on the test classpath.");
            }
            String sql = new String(is.readAllBytes(), StandardCharsets.UTF_8);
            Set<String> codes = new LinkedHashSet<>();
            Matcher m = PERM_CODE_PATTERN.matcher(sql);
            while (m.find()) {
                codes.add(m.group(1));
            }
            return codes;
        }
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not load controller class " + name, e);
        }
    }

    private static String formatPhantoms(Map<String, List<String>> phantoms) {
        if (phantoms.isEmpty()) {
            return "(none)";
        }
        var sb = new StringBuilder();
        phantoms.forEach(
                (code, handlers) ->
                        sb.append("\n  ")
                                .append(code)
                                .append(" → ")
                                .append(handlers));
        return sb.toString();
    }
}
