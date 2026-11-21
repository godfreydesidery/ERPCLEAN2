package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.sales.service.RestaurantReportQuery.ItemScope;
import org.junit.jupiter.api.Test;

/**
 * The SQL fragments of the restaurant reports' item and department filters: nothing at all when no
 * filter is set (the unfiltered reports keep their SQL), every value bound and never spliced in, and
 * the department matched the way the reports group it.
 */
class RestaurantReportItemScopeTest {

    @Test
    void noFilter_addsNoSqlAndNoParameters() {
        ItemScope none = ItemScope.NONE;
        assertThat(none.active()).isFalse();
        assertThat(none.params()).isEmpty();
        assertThat(none.lineSql("l", "p")).isEmpty();
        assertThat(none.invoiceExists("i.id")).isEmpty();
        assertThat(none.orderExists("o.id", true)).isEmpty();
        assertThat(none.productJoin("l", "p")).isEmpty();
    }

    @Test
    void product_isMatchedOnTheLineByBoundId_withoutAProductJoin() {
        ItemScope s = new ItemScope(42L, "Tacos", null);
        assertThat(s.active()).isTrue();
        assertThat(s.params()).containsExactly(42L);
        assertThat(s.lineSql("l", "p")).isEqualTo(" AND l.product_id = ?");
        assertThat(s.productJoin("l", "p")).isEmpty();
        assertThat(s.invoiceExists("i.id"))
                .isEqualTo(" AND EXISTS (SELECT 1 FROM sales_invoice_lines xl"
                        + " WHERE xl.invoice_id = i.id AND xl.product_id = ?)");
    }

    @Test
    void department_isBoundLowerCased_andMatchedOnTheReportsDepartmentLabel() {
        ItemScope s = new ItemScope(null, null, "Hot Drinks");
        assertThat(s.params()).containsExactly("hot drinks");
        assertThat(s.lineSql("l", "p")).isEqualTo(
                " AND LOWER(COALESCE(NULLIF(TRIM(p.category), ''), '(no department)')) = ?");
        assertThat(s.productJoin("xl", "xp")).isEqualTo(" LEFT JOIN products xp ON xp.id = xl.product_id");
    }

    @Test
    void department_neverReachesTheSql_evenWhenItLooksLikeSql() {
        String hostile = "x') OR 1=1 --";
        ItemScope s = new ItemScope(null, null, hostile);
        assertThat(s.lineSql("l", "p")).doesNotContain("OR 1=1");
        assertThat(s.invoiceExists("i.id")).doesNotContain("OR 1=1");
        assertThat(s.orderExists("o.id", false)).doesNotContain("OR 1=1");
        assertThat(s.params()).containsExactly(hostile.toLowerCase());
    }

    @Test
    void bothFilters_productThenDepartment_inEveryFragment() {
        ItemScope s = new ItemScope(7L, "Latte", "Drinks");
        assertThat(s.params()).containsExactly(7L, "drinks");
        String order = s.orderExists("o.id", true);
        assertThat(order).startsWith(" AND EXISTS (SELECT 1 FROM restaurant_order_lines xl"
                + " LEFT JOIN products xp ON xp.id = xl.product_id"
                + " WHERE xl.restaurant_order_id = o.id AND xl.status <> 'VOIDED'");
        assertThat(order.indexOf("xl.product_id = ?")).isLessThan(order.indexOf("LOWER("));
        assertThat(s.orderExists("o.id", false)).doesNotContain("VOIDED");
    }

    @Test
    void matchedLines_joinTheReceiptsMatchingLinesAtGross() {
        String join = new ItemScope(7L, "Latte", null).matchedGrossJoin(2);
        assertThat(join).startsWith(" LEFT JOIN LATERAL (SELECT SUM(")
                .contains("FROM sales_invoice_lines ml WHERE ml.invoice_id = i.id AND ml.product_id = ?")
                .endsWith(") m ON true");
    }
}
