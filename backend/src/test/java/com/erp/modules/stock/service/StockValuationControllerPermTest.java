package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.api.StockValuationController;
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
 * Regression test — Bug #2: stock valuation report returned 403 for rootadmin.
 *
 * <p>Root cause: {@code StockValuationController.report()} used
 * {@code @PreAuthorize("hasAuthority('INVENTORY.VALUATION.VIEW')")}. Spring Security's
 * {@code hasAuthority} checks the {@code GrantedAuthority} list populated from the JWT
 * {@code scope} claim — root's JWT carries no permission claims, so the check always denied root.
 * The {@code @perm.has()} expression correctly short-circuits for root via
 * {@link com.erp.platform.security.PermissionResolver#hasPermission}.
 *
 * <p>Fix: changed to {@code @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW')")}.
 * This test asserts that the annotation uses the {@code @perm.has} form so the regression
 * cannot silently be re-introduced.
 *
 * <p>The export handler is held to the same form PLUS {@code REPORT.EXPORT}: a download discloses
 * strictly more than one screen, so it must never open for a caller the screen itself refuses.
 */
class StockValuationControllerPermTest {

    /** Matches each {@code @perm.has('CODE')} term in a {@code @PreAuthorize} expression. */
    private static final Pattern PERM_HAS = Pattern.compile("@perm\\.has\\('([^']+)'\\)");

    private static final String VIEW   = "INVENTORY.VALUATION.VIEW";
    private static final String EXPORT = "REPORT.EXPORT";

    private static final List<String> EXPORT_HANDLERS = List.of("exportReport");

    @Test
    void reportEndpoint_usesPermHasNotHasAuthority() throws NoSuchMethodException {
        Method report = StockValuationController.class.getDeclaredMethod("report",
                java.time.LocalDate.class);

        PreAuthorize annotation = report.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("report() must carry @PreAuthorize")
                .isNotNull();

        String value = annotation.value();
        assertThat(value)
                .as("report() must use @perm.has (root-bypass aware), not hasAuthority (JWT-claims only)")
                .contains("@perm.has");
        assertThat(value)
                .as("report() must not use hasAuthority (root bypass does NOT work through hasAuthority)")
                .doesNotContain("hasAuthority");
    }

    @Test
    void openingEndpoint_usesPermHasNotHasAuthority() throws NoSuchMethodException {
        Method opening = StockValuationController.class.getDeclaredMethod("setOpening",
                com.erp.modules.stock.domain.dto.SetOpeningValuationRequest.class);

        PreAuthorize annotation = opening.getAnnotation(PreAuthorize.class);
        assertThat(annotation)
                .as("setOpening() must carry @PreAuthorize")
                .isNotNull();

        String value = annotation.value();
        assertThat(value)
                .as("setOpening() must use @perm.has (root-bypass aware)")
                .contains("@perm.has");
        assertThat(value)
                .as("setOpening() must not use hasAuthority")
                .doesNotContain("hasAuthority");
    }

    // -------------------------------------------------------------------------
    // The export needs BOTH codes; neither one alone opens the download
    // -------------------------------------------------------------------------

    @Test
    void exportEndpoint_usesPermHasNotHasAuthority() {
        for (String name : EXPORT_HANDLERS) {
            assertThat(gateOf(name))
                    .as("%s must use @perm.has (root-bypass aware), not hasAuthority", name)
                    .contains("@perm.has")
                    .doesNotContain("hasAuthority");
        }
    }

    @Test
    void export_deniedWithExportPermissionAlone() {
        for (String name : EXPORT_HANDLERS) {
            assertThat(evaluateGate(gateOf(name), Set.of(EXPORT)))
                    .as("a caller refused the on-screen valuation report must not be able to "
                            + "download it (%s)", name)
                    .isFalse();
        }
    }

    @Test
    void export_deniedWithViewPermissionAlone() {
        for (String name : EXPORT_HANDLERS) {
            assertThat(evaluateGate(gateOf(name), Set.of(VIEW)))
                    .as("%s must still require %s — seeing a report is not permission to take it "
                            + "away", name, EXPORT)
                    .isFalse();
        }
    }

    @Test
    void export_allowedOnlyWithBothPermissions() {
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

    /** The live {@code @PreAuthorize} expression on the named handler. */
    private static String gateOf(String methodName) {
        Method handler = null;
        for (Method m : StockValuationController.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                handler = m;
            }
        }
        assertThat(handler)
                .as("StockValuationController#%s must exist", methodName)
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
