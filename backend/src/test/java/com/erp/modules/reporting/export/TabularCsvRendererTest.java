package com.erp.modules.reporting.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests for {@link TabularCsvRenderer} — deterministic byte content for a fixed model,
 * plus the same formula-injection / quoting regression guards as {@code CsvStatementRendererTest}.
 */
class TabularCsvRendererTest {

    private final TabularCsvRenderer renderer = new TabularCsvRenderer();

    private TabularRenderModel model(List<String> headerLines, List<List<String>> rows,
                                      List<String> totalsRow) {
        return new TabularRenderModel(
                "Sales Report",
                headerLines,
                "2026-07-19T10:00:00Z",
                List.of(new Column("Code", Align.LEFT),
                        new Column("Description", Align.LEFT),
                        new Column("Qty", Align.RIGHT),
                        new Column("Amount", Align.RIGHT)),
                rows,
                totalsRow);
    }

    private String render(TabularRenderModel model) {
        return new String(renderer.render(model), StandardCharsets.UTF_8);
    }

    @Test
    void deterministicContent_forAFixedModel() {
        String csv = render(model(
                List.of("Acme Ltd", "Dar es Salaam"),
                List.of(List.of("P001", "Widget", "10", "1,000.00"),
                        List.of("P002", "Gadget", "5", "500.00")),
                List.of("", "TOTAL", "15", "1,500.00")));

        List<String> lines = csv.lines().toList();
        assertThat(lines).containsExactly(
                "Sales Report",
                "Acme Ltd",
                "Dar es Salaam",
                "Generated,2026-07-19T10:00:00Z",
                "",
                "Code,Description,Qty,Amount",
                "P001,Widget,10,\"1,000.00\"",
                "P002,Gadget,5,500.00",
                ",TOTAL,15,\"1,500.00\"");
    }

    @Test
    void noHeaderLinesOrTotalsRow_omitsThoseLines() {
        String csv = render(model(List.of(), List.of(List.of("P001", "Widget", "10", "100.00")), null));

        List<String> lines = csv.lines().toList();
        assertThat(lines).containsExactly(
                "Sales Report",
                "Generated,2026-07-19T10:00:00Z",
                "",
                "Code,Description,Qty,Amount",
                "P001,Widget,10,100.00");
    }

    @Test
    void formulaInjection_inCellValue_isNeutralised() {
        String csv = render(model(List.of(), List.of(List.of("=cmd|'/c calc'!A1", "x", "1", "1")), null));
        assertThat(csv).contains("'=cmd|'/c calc'!A1");
        assertThat(csv.lines().anyMatch(l -> l.startsWith("=cmd"))).isFalse();
    }

    @Test
    void cellWithComma_isQuotedExactlyOnce() {
        String csv = render(model(List.of(), List.of(List.of("P001", "Rent, office", "1", "1")), null));
        assertThat(csv).contains("\"Rent, office\"");
        assertThat(csv).doesNotContain("\"\"\"Rent");
    }
}
