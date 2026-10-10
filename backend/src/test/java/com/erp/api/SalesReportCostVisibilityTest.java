package com.erp.api;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.sales.domain.dto.SalesReportDto;
import com.erp.modules.sales.domain.dto.SalesReportRowDto;
import com.erp.modules.sales.domain.dto.SalesReportTotalsDto;
import com.erp.modules.sales.domain.dto.SalesSummaryReportDto;
import com.erp.modules.sales.domain.dto.SalesSummaryRowDto;
import com.erp.modules.sales.domain.dto.SalesSummaryTotalsDto;
import com.erp.modules.sales.domain.enums.SalesSummaryGroupBy;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Owner ruling 2026-10-10 (ADM-14): counter staff keep the Sales Report and Sales Summary but not
 * cost of sales or margin. The fields stay (deployed apps parse them) and read null, flagged
 * {@code costVisible=false}; the export drops the columns.
 */
class SalesReportCostVisibilityTest {

    private static final BigDecimal TEN = BigDecimal.TEN;

    private static SalesReportDto salesReport() {
        SalesReportRowDto row = new SalesReportRowDto("P1", "Soap", TEN, TEN, BigDecimal.ONE,
                BigDecimal.ONE, new BigDecimal("3"), new BigDecimal("12"));
        return new SalesReportDto(null, "2026-10-01", "2026-10-10", null, null, null, "TZS",
                List.of(row), new SalesReportTotalsDto(TEN, BigDecimal.ONE, BigDecimal.ONE,
                new BigDecimal("3"), new BigDecimal("12"), 0), "now");
    }

    @Test
    void salesReport_withoutCost_nullsEveryMargin_andFlagsIt() {
        SalesReportDto masked = salesReport().withoutCost();

        assertThat(masked.costVisible()).isFalse();
        assertThat(masked.rows()).allSatisfy(r -> assertThat(r.margin()).isNull());
        assertThat(masked.totals().margin()).isNull();
        assertThat(masked.totals().marginRowsUnknown())
                .as("an older client reading only the count shows 'no margin', never zero")
                .isEqualTo(masked.rows().size());
        assertThat(masked.totals().amount()).isEqualByComparingTo("12");
        assertThat(salesReport().costVisible()).as("the original shape stays visible").isTrue();
    }

    @Test
    void salesReport_export_dropsTheMarginColumn_onlyWhenWithheld() {
        SalesReportController controller = new SalesReportController(null, null, null, null);

        TabularRenderModel shown = controller.flatten(salesReport(), "Branch: All branches");
        TabularRenderModel hidden = controller.flatten(salesReport().withoutCost(), "Branches: A");

        assertThat(shown.columns()).extracting(TabularRenderModel.Column::header).contains("Margin");
        assertThat(hidden.columns()).extracting(TabularRenderModel.Column::header)
                .doesNotContain("Margin");
        assertThat(hidden.rows().get(0)).hasSize(hidden.columns().size());
        assertThat(hidden.totalsRow()).hasSize(hidden.columns().size());
        assertThat(hidden.headerLines()).contains("Branches: A");
    }

    @Test
    void salesSummary_withoutCost_nullsCostMarginAndPercent() {
        SalesSummaryRowDto row = new SalesSummaryRowDto("k", "Walk-in", null, 2, TEN, TEN,
                BigDecimal.ZERO, BigDecimal.ONE, new BigDecimal("9"), new BigDecimal("6"),
                new BigDecimal("3"), new BigDecimal("33.33"), 0, 0);
        SalesSummaryReportDto dto = new SalesSummaryReportDto(null, "a", "b",
                SalesSummaryGroupBy.CUSTOMER, null, "TZS", List.of(row),
                new SalesSummaryTotalsDto(2, TEN, TEN, BigDecimal.ZERO, BigDecimal.ONE,
                        new BigDecimal("9"), new BigDecimal("6"), new BigDecimal("3"),
                        new BigDecimal("33.33"), 0, 0), "now");

        SalesSummaryReportDto masked = dto.withoutCost();

        assertThat(masked.costVisible()).isFalse();
        assertThat(masked.rows().get(0).costOfSales()).isNull();
        assertThat(masked.rows().get(0).margin()).isNull();
        assertThat(masked.rows().get(0).marginPercent()).isNull();
        assertThat(masked.totals().costOfSales()).isNull();
        assertThat(masked.totals().margin()).isNull();
        assertThat(masked.totals().netAmount()).isEqualByComparingTo("9");
    }
}
