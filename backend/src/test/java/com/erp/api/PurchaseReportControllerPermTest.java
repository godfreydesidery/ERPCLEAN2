package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.purchases.service.GoodsReceivedRegisterQuery;
import com.erp.modules.purchases.service.OpenPurchaseOrdersQuery;
import com.erp.modules.purchases.service.PurchasePriceVarianceQuery;
import com.erp.modules.purchases.service.PurchasesBySupplierQuery;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.platform.security.PermissionChecks;
import com.erp.platform.security.RequestContext;
import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.expression.AccessException;
import org.springframework.expression.BeanResolver;
import org.springframework.expression.EvaluationContext;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.security.access.prepost.PreAuthorize;

/**
 * Gates on the four purchase reports, and the per-column disclosure behind them.
 *
 * <p>Each report reuses the code of the screen that already shows the same figures; columns from a
 * module behind a further code (supplier bills: {@code AP.VIEW}; purchase returns:
 * {@code PURCHASE.RETURN.VIEW}) are withheld inside the handler rather than refusing the whole
 * report. Exports need the screen gate AND {@code REPORT.EXPORT}.
 */
class PurchaseReportControllerPermTest {

    private static final Pattern PERM_HAS = Pattern.compile("@perm\\.has\\('([^']+)'\\)");

    private static final String GRN    = "PURCHASE.GOODS_RECEIPT.VIEW";
    private static final String PO     = "PURCHASE.ORDER.VIEW";
    private static final String EXPORT = "REPORT.EXPORT";

    @AfterEach
    void clear() {
        RequestContext.clear();
    }

    @Test
    void screenGates_areTheCodesOfTheScreensThatAlreadyShowTheseFigures() {
        assertThat(codesIn(gateOf("goodsReceived"))).containsExactly(GRN);
        assertThat(codesIn(gateOf("bySupplier"))).containsExactly(GRN);
        assertThat(codesIn(gateOf("openOrders"))).containsExactly(PO);
        assertThat(codesIn(gateOf("priceVariance"))).containsExactlyInAnyOrder(PO, GRN);
    }

    @Test
    void priceVariance_needsBothOrderAndReceiptView() {
        String gate = gateOf("priceVariance");
        assertThat(evaluate(gate, Set.of(PO))).isFalse();
        assertThat(evaluate(gate, Set.of(GRN))).isFalse();
        assertThat(evaluate(gate, Set.of(PO, GRN))).isTrue();
    }

    @Test
    void everyExport_needsItsScreenGateAndExport_neitherAlone() {
        String[][] pairs = {
                {"goodsReceived", "exportGoodsReceived"},
                {"bySupplier", "exportBySupplier"},
                {"openOrders", "exportOpenOrders"},
                {"priceVariance", "exportPriceVariance"},
        };
        for (String[] pair : pairs) {
            Set<String> screen = codesIn(gateOf(pair[0]));
            String exportGate = gateOf(pair[1]);
            assertThat(gateOf(pair[1])).contains("@perm.has").doesNotContain("hasAuthority");
            assertThat(evaluate(exportGate, Set.of(EXPORT))).as(pair[1] + " with export alone").isFalse();
            assertThat(evaluate(exportGate, screen)).as(pair[1] + " with the screen gate alone").isFalse();
            Set<String> both = new LinkedHashSet<>(screen);
            both.add(EXPORT);
            assertThat(evaluate(exportGate, both)).as(pair[1] + " with both").isTrue();
        }
    }

    @Test
    void billAndReturnColumns_followTheCallersOwnCodes() {
        RequestContext.set(new RequestContext.Principal(1L, "u", false, 7L, 1L, null));
        PurchasesBySupplierQuery bySupplier = mock(PurchasesBySupplierQuery.class);
        PurchasePriceVarianceQuery variance = mock(PurchasePriceVarianceQuery.class);
        PermissionChecks perm = mock(PermissionChecks.class);
        when(perm.has("AP.VIEW")).thenReturn(false);
        when(perm.has("PURCHASE.RETURN.VIEW")).thenReturn(true);

        PurchaseReportController controller = new PurchaseReportController(
                mock(GoodsReceivedRegisterQuery.class), bySupplier, mock(OpenPurchaseOrdersQuery.class),
                variance, mock(TabularExporter.class), perm);

        LocalDate d = LocalDate.of(2026, 3, 1);
        controller.bySupplier(d, d, null);
        verify(bySupplier).report(eq(7L), eq(d), eq(d), isNull(), eq(true), eq(false));

        controller.priceVariance(d, d, null, null);
        verify(variance).report(eq(7L), eq(d), eq(d), isNull(), isNull(), eq(false));

        when(perm.has("AP.VIEW")).thenReturn(true);
        controller.priceVariance(d, d, "B", null);
        verify(variance).report(eq(7L), any(), any(), eq("B"), isNull(), eq(true));
        verify(variance, org.mockito.Mockito.times(2))
                .report(any(), any(), any(), any(), any(), anyBoolean());
    }

    // -------------------------------------------------------------------------

    private static String gateOf(String methodName) {
        Method handler = null;
        for (Method m : PurchaseReportController.class.getDeclaredMethods()) {
            if (m.getName().equals(methodName)) {
                handler = m;
            }
        }
        assertThat(handler).as("PurchaseReportController#%s must exist", methodName).isNotNull();
        PreAuthorize gate = handler.getAnnotation(PreAuthorize.class);
        assertThat(gate).as("%s must carry @PreAuthorize", methodName).isNotNull();
        return gate.value();
    }

    private static boolean evaluate(String expression, Set<String> held) {
        PermissionChecks perm = mock(PermissionChecks.class);
        for (String code : codesIn(expression)) {
            when(perm.has(code)).thenReturn(held.contains(code));
        }
        StandardEvaluationContext context = new StandardEvaluationContext();
        context.setBeanResolver(new BeanResolver() {
            @Override
            public Object resolve(EvaluationContext ctx, String beanName) throws AccessException {
                if ("perm".equals(beanName)) {
                    return perm;
                }
                throw new AccessException("Unexpected bean: " + beanName);
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
