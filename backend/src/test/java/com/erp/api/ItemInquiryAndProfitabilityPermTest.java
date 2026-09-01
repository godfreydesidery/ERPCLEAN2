package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.platform.security.PermissionChecks;
import java.lang.reflect.Method;
import java.util.LinkedHashSet;
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
 * Permission gates on the two screens added for K-2026-08-30 — Item Inquiry (#3) and the
 * Profitability Report (#2).
 *
 * <p>Both deliberately depart from the nearest existing gate, and the reasoning is what this test
 * pins down:
 *
 * <ul>
 *   <li><b>Item Inquiry</b> is NOT gated on {@code INVENTORY.VALUATION.VIEW} like the product-stock
 *       registers, because those return the whole catalogue's buying prices in one call while this
 *       answers one customer's question about one item. The standing rule that a cashier must not
 *       learn an item's margin is kept as a hidden COLUMN instead — read
 *       {@code ItemInquiryController#inquire}, which asks {@code perm.has} for the valuation code
 *       and passes the answer down. The gate itself is a conjunction, so a caller holding only one
 *       of the two read codes is refused rather than served a half-answer.</li>
 *   <li><b>Profitability</b> matches {@code SalesReportController} exactly. That report already
 *       discloses margin at {@code SALES.INVOICE.VIEW}, so cost of sales here is the same
 *       disclosure to the same audience; a second gate would lock out the manager who asked for it
 *       while protecting nothing.</li>
 * </ul>
 *
 * <p>And the invariant both share: an export requires everything its on-screen sibling requires
 * PLUS {@code REPORT.EXPORT}. A download discloses strictly more than one page.
 */
class ItemInquiryAndProfitabilityPermTest {

    /** Matches each {@code @perm.has('CODE')} term in a {@code @PreAuthorize} expression. */
    private static final Pattern PERM_HAS = Pattern.compile("@perm\\.has\\('([^']+)'\\)");

    private static final String PRODUCT_VIEW = "PRODUCT.VIEW";
    private static final String STOCK_VIEW   = "STOCK.VIEW";
    private static final String VALUATION    = "INVENTORY.VALUATION.VIEW";
    private static final String SALES_VIEW   = "SALES.INVOICE.VIEW";
    private static final String EXPORT       = "REPORT.EXPORT";

    // -------------------------------------------------------------------------
    // Item Inquiry
    // -------------------------------------------------------------------------

    @Test
    void itemInquiry_isGatedWithPermHas_notHasAuthority() {
        String gate = gateOf(ItemInquiryController.class, "inquire");
        assertThat(gate)
                .as("must use @perm.has (root-bypass aware), not hasAuthority (JWT claims only, "
                        + "which denies rootadmin)")
                .contains("@perm.has")
                .doesNotContain("hasAuthority");
    }

    @Test
    void itemInquiry_needsBothReadCodes_neitherAlone() {
        String gate = gateOf(ItemInquiryController.class, "inquire");

        assertThat(evaluateGate(gate, Set.of()))
                .as("a caller holding nothing must be refused").isFalse();
        assertThat(evaluateGate(gate, Set.of(PRODUCT_VIEW)))
                .as("PRODUCT.VIEW alone cannot read stock quantities").isFalse();
        assertThat(evaluateGate(gate, Set.of(STOCK_VIEW)))
                .as("STOCK.VIEW alone cannot read the product catalogue").isFalse();
        assertThat(evaluateGate(gate, Set.of(PRODUCT_VIEW, STOCK_VIEW)))
                .as("both codes together open the lookup — what every counter role already holds")
                .isTrue();
    }

    /**
     * The departure worth protecting: requiring the valuation code here would take the item lookup
     * away from cashiers and salespeople, who are exactly the people a customer asks. Cost is
     * withheld from them instead, inside the handler.
     */
    @Test
    void itemInquiry_isNotGatedOnTheValuationCode() {
        assertThat(codesIn(gateOf(ItemInquiryController.class, "inquire")))
                .as("cost is hidden per-column, not by refusing the whole lookup")
                .doesNotContain(VALUATION);
    }

    // -------------------------------------------------------------------------
    // Profitability Report
    // -------------------------------------------------------------------------

    @Test
    void profitability_isGatedWithPermHas_notHasAuthority() {
        for (String name : new String[]{"profitability", "exportProfitability"}) {
            assertThat(gateOf(ProfitabilityReportController.class, name))
                    .as("%s must use @perm.has, not hasAuthority", name)
                    .contains("@perm.has")
                    .doesNotContain("hasAuthority");
        }
    }

    @Test
    void profitability_matchesTheSalesReportGate() {
        assertThat(codesIn(gateOf(ProfitabilityReportController.class, "profitability")))
                .as("same gate as the Sales Report, which already discloses margin")
                .containsExactly(SALES_VIEW);
    }

    @Test
    void profitability_onScreen_allowedWithTheSalesViewCodeAlone() {
        assertThat(evaluateGate(gateOf(ProfitabilityReportController.class, "profitability"),
                Set.of(SALES_VIEW)))
                .isTrue();
    }

    @Test
    void profitability_export_needsTheScreenGateAndExport_neitherAlone() {
        String gate = gateOf(ProfitabilityReportController.class, "exportProfitability");

        assertThat(evaluateGate(gate, Set.of(EXPORT)))
                .as("a caller refused the on-screen report must not be able to download it")
                .isFalse();
        assertThat(evaluateGate(gate, Set.of(SALES_VIEW)))
                .as("seeing a report is not permission to take it away")
                .isFalse();
        assertThat(evaluateGate(gate, Set.of(SALES_VIEW, EXPORT)))
                .as("both together open the download")
                .isTrue();
    }

    // -------------------------------------------------------------------------
    // Helpers — same harness as ProductStockReportControllerPermTest (no Spring context,
    // so this stays a fast surefire test).
    // -------------------------------------------------------------------------

    private static String gateOf(Class<?> controller, String methodName) {
        Method handler = null;
        for (Method m : controller.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                handler = m;
            }
        }
        assertThat(handler)
                .as("%s#%s must exist", controller.getSimpleName(), methodName)
                .isNotNull();
        PreAuthorize gate = handler.getAnnotation(PreAuthorize.class);
        assertThat(gate).as("%s must carry @PreAuthorize", methodName).isNotNull();
        return gate.value();
    }

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
