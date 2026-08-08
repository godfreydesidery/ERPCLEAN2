package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.platform.security.PermissionChecks;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
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
import org.springframework.expression.AccessException;
import org.springframework.expression.BeanResolver;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * A download must never be reachable by a caller the on-screen report refuses (persona UAT B5).
 *
 * <p>The defect this locks down: {@code GET /api/v1/reports/stock-movement} was gated on
 * {@code INVENTORY.VALUATION.VIEW} while its {@code /export} sibling was gated on
 * {@code REPORT.EXPORT} alone. The seeded ACCOUNTANT role holds the latter and not the former, so a
 * user refused the report on screen could download the identical data as a file — the permission
 * model was inconsistent in the UNSAFE direction, since an export discloses strictly more than one
 * page of a paged report.
 *
 * <p>Two guards:
 * <ol>
 *   <li>{@link #exportGatesAreNeverWeakerThanTheirViewGate()} — a systemic scan of every
 *       {@code .../export} handler under {@code com.erp.api}: the export's required permission codes
 *       must be a SUPERSET of its view sibling's. One inversion is a bug; the pattern was the hole.</li>
 *   <li>{@link #stockMovementExport_deniedWithExportPermissionAlone()} and its sibling — the actual
 *       SpEL expression evaluated against a principal holding only {@code REPORT.EXPORT} (refused)
 *       and one holding both codes (allowed).</li>
 * </ol>
 */
class ExportPermissionGateTest {

    /** Matches each {@code @perm.has('CODE')} term in a {@code @PreAuthorize} expression. */
    private static final Pattern PERM_HAS = Pattern.compile("@perm\\.has\\('([^']+)'\\)");

    private static final String STOCK_MOVEMENT_EXPORT_METHOD = "exportStockMovementReport";

    // -------------------------------------------------------------------------
    // 1 — Systemic: no export gate may be weaker than its view gate
    // -------------------------------------------------------------------------

    @Test
    void exportGatesAreNeverWeakerThanTheirViewGate() {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));

        List<String> inversions = new ArrayList<>();
        int compared = 0;

        for (var bean : scanner.findCandidateComponents("com.erp.api")) {
            Class<?> controller = load(bean.getBeanClassName());
            // GET handlers only: a report and its export are both reads, and indexing writes by path
            // would collide with the read at the same collection path.
            Map<String, Method> handlersByPath = new LinkedHashMap<>();
            for (Method method : controller.getDeclaredMethods()) {
                if (isRequestHandler(method) && method.isAnnotationPresent(GetMapping.class)) {
                    handlersByPath.put(mappedPath(method), method);
                }
            }

            for (var entry : handlersByPath.entrySet()) {
                String path = entry.getKey();
                if (!path.endsWith("/export")) {
                    continue;
                }
                String viewPath = path.substring(0, path.length() - "/export".length());
                Method view = handlersByPath.get(viewPath);
                if (view == null) {
                    continue; // no on-screen sibling in this controller — nothing to compare against
                }
                Set<String> viewCodes   = requiredCodes(view);
                Set<String> exportCodes = requiredCodes(entry.getValue());
                if (viewCodes.isEmpty() && exportCodes.isEmpty()) {
                    continue; // both gated by a custom bean expression (e.g. @bulkAccess) — not comparable
                }
                compared++;
                if (!exportCodes.containsAll(viewCodes)) {
                    Set<String> missing = new LinkedHashSet<>(viewCodes);
                    missing.removeAll(exportCodes);
                    inversions.add(controller.getSimpleName() + "#" + entry.getValue().getName()
                            + " is missing " + missing + " (its view sibling requires " + viewCodes
                            + ", the export only requires " + exportCodes + ")");
                }
            }
        }

        assertThat(compared)
                .as("the scan must actually find export/view pairs — a zero here means the scan broke, "
                        + "not that the codebase is clean")
                .isGreaterThanOrEqualTo(7);
        assertThat(inversions)
                .as("An export discloses strictly more than one on-screen page, so its gate must "
                        + "require everything the view gate requires (plus any export permission). "
                        + "Offenders: %s", inversions)
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // 2 — Behavioural: evaluate the stock-movement export expression itself
    // -------------------------------------------------------------------------

    @Test
    void stockMovementExport_deniedWithExportPermissionAlone() {
        // The seeded ACCOUNTANT role exactly: REPORT.EXPORT, no INVENTORY.VALUATION.VIEW.
        assertThat(evaluateGate(stockMovementExportGate(), Set.of("REPORT.EXPORT")))
                .as("a caller refused the on-screen stock-movement report must not be able to "
                        + "download the same data")
                .isFalse();
    }

    @Test
    void stockMovementExport_allowedOnlyWithBothPermissions() {
        assertThat(evaluateGate(stockMovementExportGate(),
                Set.of("INVENTORY.VALUATION.VIEW", "REPORT.EXPORT")))
                .as("a caller who may both see the report and export reports keeps the download")
                .isTrue();
    }

    @Test
    void stockMovementExport_deniedWithViewPermissionAlone() {
        assertThat(evaluateGate(stockMovementExportGate(), Set.of("INVENTORY.VALUATION.VIEW")))
                .as("the export permission is still required — the fix must not drop REPORT.EXPORT")
                .isFalse();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** The live {@code @PreAuthorize} expression on the stock-movement export handler. */
    private static String stockMovementExportGate() {
        Method export = null;
        for (Method m : StockMovementReportController.class.getDeclaredMethods()) {
            if (m.getName().equals(STOCK_MOVEMENT_EXPORT_METHOD)) {
                export = m;
            }
        }
        assertThat(export).as("StockMovementReportController#%s must exist",
                STOCK_MOVEMENT_EXPORT_METHOD).isNotNull();
        PreAuthorize gate = export.getAnnotation(PreAuthorize.class);
        assertThat(gate).as("the export handler must carry @PreAuthorize").isNotNull();
        return gate.value();
    }

    /**
     * Evaluates a {@code @PreAuthorize} expression against a principal holding exactly
     * {@code heldCodes}, by resolving the {@code perm} bean to a stub {@link PermissionChecks}.
     */
    private static boolean evaluateGate(String expression, Set<String> heldCodes) {
        PermissionChecks perm = mock(PermissionChecks.class);
        for (String code : allCodesIn(expression)) {
            when(perm.has(code)).thenReturn(heldCodes.contains(code));
        }
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver(new BeanResolver() {
            @Override
            public Object resolve(EvaluationContext ctx, String beanName) throws AccessException {
                if ("perm".equals(beanName)) {
                    return perm;
                }
                throw new AccessException("Unexpected bean in a permission expression: " + beanName);
            }
        });
        return Boolean.TRUE.equals(
                new SpelExpressionParser().parseExpression(expression).getValue(context, Boolean.class));
    }

    /**
     * The permission codes a gate REQUIRES. Only pure conjunctions of {@code @perm.has(...)} are
     * treated as requirements: an expression containing {@code or} (a read-floor / multi-role gate)
     * requires none of its codes individually, so it is reported as an empty set rather than
     * misread as a requirement.
     */
    private static Set<String> requiredCodes(Method handler) {
        PreAuthorize gate = handler.getAnnotation(PreAuthorize.class);
        if (gate == null) {
            return Set.of();
        }
        String expression = gate.value();
        if (expression.matches("(?s).*\\bor\\b.*")) {
            return Set.of();
        }
        return allCodesIn(expression);
    }

    private static Set<String> allCodesIn(String expression) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher m = PERM_HAS.matcher(expression);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }

    /** Class-level {@code @RequestMapping} path + the handler's own mapped path. */
    private static String mappedPath(Method handler) {
        String base = "";
        RequestMapping classMapping = handler.getDeclaringClass().getAnnotation(RequestMapping.class);
        if (classMapping != null && classMapping.value().length > 0) {
            base = classMapping.value()[0];
        }
        String own = "";
        GetMapping get = handler.getAnnotation(GetMapping.class);
        if (get != null && get.value().length > 0) {
            own = get.value()[0];
        } else {
            RequestMapping methodMapping = handler.getAnnotation(RequestMapping.class);
            if (methodMapping != null && methodMapping.value().length > 0) {
                own = methodMapping.value()[0];
            }
        }
        return base + own;
    }

    private static boolean isRequestHandler(Method method) {
        for (Annotation a : method.getAnnotations()) {
            Class<? extends Annotation> type = a.annotationType();
            if (type.isAnnotationPresent(RequestMapping.class) || type.equals(RequestMapping.class)) {
                return true;
            }
        }
        return false;
    }

    private static Class<?> load(String name) {
        try {
            return Class.forName(name);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Could not load controller " + name, e);
        }
    }
}
