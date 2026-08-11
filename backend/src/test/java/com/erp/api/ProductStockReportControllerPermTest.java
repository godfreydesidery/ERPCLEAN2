package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.platform.security.PermissionChecks;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.springframework.expression.AccessException;
import org.springframework.expression.BeanResolver;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Permission gates on the two product-stock registers.
 *
 * <p>Both reports disclose buying prices and therefore margins, so both are gated on
 * {@code INVENTORY.VALUATION.VIEW} rather than a product-read code — a cashier who may look a
 * product up must not thereby learn what the shop pays for it.
 *
 * <p>Two regressions are locked down here:
 * <ol>
 *   <li>{@code @perm.has}, never {@code hasAuthority}. {@code hasAuthority} reads the
 *       {@code GrantedAuthority} list built from the JWT scope claim, and root's JWT carries no
 *       permission claims — so a {@code hasAuthority} gate denies rootadmin (the Bug #2 shape locked
 *       down by {@code StockValuationControllerPermTest}).</li>
 *   <li>An export must require everything its on-screen sibling requires, PLUS
 *       {@code REPORT.EXPORT}. A download discloses strictly more than one page of a report, so a
 *       caller refused the screen must not be able to fetch the same data as a file — the inversion
 *       {@code ExportPermissionGateTest} exists to prevent.</li>
 * </ol>
 */
class ProductStockReportControllerPermTest {

    /** Matches each {@code @perm.has('CODE')} term in a {@code @PreAuthorize} expression. */
    private static final Pattern PERM_HAS = Pattern.compile("@perm\\.has\\('([^']+)'\\)");

    private static final String VIEW   = "INVENTORY.VALUATION.VIEW";
    private static final String EXPORT = "REPORT.EXPORT";

    private static final List<String> VIEW_HANDLERS   = List.of("productList", "stockValue");
    private static final List<String> EXPORT_HANDLERS = List.of("exportProductList", "exportStockValue");

    // -------------------------------------------------------------------------
    // 1 — every handler is gated, and gated the root-aware way
    // -------------------------------------------------------------------------

    @Test
    void everyHandler_isGatedWithPermHas_notHasAuthority() {
        for (String name : allHandlers()) {
            String gate = gateOf(name);
            assertThat(gate)
                    .as("%s must use @perm.has (root-bypass aware), not hasAuthority "
                            + "(JWT-claims only, which denies rootadmin)", name)
                    .contains("@perm.has")
                    .doesNotContain("hasAuthority");
        }
    }

    @Test
    void everyHandler_requiresTheValuationViewPermission() {
        for (String name : allHandlers()) {
            assertThat(evaluateGate(gateOf(name), Set.of()))
                    .as("%s must refuse a caller holding no permissions at all", name)
                    .isFalse();
            assertThat(codesIn(gateOf(name)))
                    .as("%s discloses buying prices, so it must be gated on %s", name, VIEW)
                    .contains(VIEW);
        }
    }

    // -------------------------------------------------------------------------
    // 2 — the on-screen reports need the view code and nothing more
    // -------------------------------------------------------------------------

    @Test
    void onScreenReports_allowedWithTheValuationViewPermissionAlone() {
        for (String name : VIEW_HANDLERS) {
            assertThat(evaluateGate(gateOf(name), Set.of(VIEW)))
                    .as("%s is a read the storekeeper role is meant to have", name)
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // 3 — the exports need BOTH codes; neither one alone opens the download
    // -------------------------------------------------------------------------

    @Test
    void exports_deniedWithExportPermissionAlone() {
        for (String name : EXPORT_HANDLERS) {
            assertThat(evaluateGate(gateOf(name), Set.of(EXPORT)))
                    .as("a caller refused the on-screen report must not be able to download it (%s)",
                            name)
                    .isFalse();
        }
    }

    @Test
    void exports_deniedWithViewPermissionAlone() {
        for (String name : EXPORT_HANDLERS) {
            assertThat(evaluateGate(gateOf(name), Set.of(VIEW)))
                    .as("%s must still require %s — seeing a report is not permission to take it away",
                            name, EXPORT)
                    .isFalse();
        }
    }

    @Test
    void exports_allowedOnlyWithBothPermissions() {
        for (String name : EXPORT_HANDLERS) {
            assertThat(evaluateGate(gateOf(name), Set.of(VIEW, EXPORT)))
                    .as("%s must open for a caller who may both see the report and export reports",
                            name)
                    .isTrue();
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static List<String> allHandlers() {
        return List.of("productList", "exportProductList", "stockValue", "exportStockValue");
    }

    /** The live {@code @PreAuthorize} expression on the named handler. */
    private static String gateOf(String methodName) {
        Method handler = null;
        for (Method m : ProductStockReportController.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                handler = m;
            }
        }
        assertThat(handler)
                .as("ProductStockReportController#%s must exist", methodName)
                .isNotNull();
        PreAuthorize gate = handler.getAnnotation(PreAuthorize.class);
        assertThat(gate).as("%s must carry @PreAuthorize", methodName).isNotNull();
        return gate.value();
    }

    /**
     * Evaluates a {@code @PreAuthorize} expression against a principal holding exactly
     * {@code heldCodes}, by resolving the {@code perm} bean to a stub {@link PermissionChecks}.
     * Same harness as {@code ExportPermissionGateTest} — no Spring context, so it stays a fast
     * surefire test.
     */
    private static boolean evaluateGate(String expression, Set<String> heldCodes) {
        PermissionChecks perm = mock(PermissionChecks.class);
        for (String code : codesIn(expression)) {
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

    private static Set<String> codesIn(String expression) {
        Set<String> codes = new LinkedHashSet<>();
        Matcher m = PERM_HAS.matcher(expression);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }
}
