package com.erp.modules.reporting.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.reporting.export.StatementRenderModel.Row;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests for {@link CsvStatementRenderer}.
 *
 * <p>Regression guard for the adversarial-review findings: (1) CSV formula injection — a text cell
 * starting with = + - @ must be neutralised with a leading apostrophe; (2) numeric amounts (incl.
 * negatives) must NOT be mangled; (3) labels must be escaped exactly once (no double-quoting).
 */
class CsvStatementRendererTest {

    private final CsvStatementRenderer renderer = new CsvStatementRenderer();

    private String render(String companyName, List<Row> rows) {
        StatementRenderModel model = new StatementRenderModel(
                "Balance Sheet", companyName, "TZS",
                "As at 2026-06-30", "As at 2025-12-31", "2026-06-30T10:00:00Z", rows);
        return new String(renderer.render(model), StandardCharsets.UTF_8);
    }

    @Test
    void formulaInjection_inCompanyName_isNeutralised() {
        String csv = render("=cmd|'/c calc'!A1", List.of(Row.line("Cash", new BigDecimal("100"), null)));
        // The dangerous cell is prefixed with a single quote and never appears as a bare formula line.
        assertThat(csv).contains("'=cmd|'/c calc'!A1");
        assertThat(csv.lines().anyMatch(l -> l.startsWith("=cmd"))).isFalse();
    }

    @Test
    void formulaInjection_inRowLabel_isNeutralised() {
        String csv = render("Acme Co", List.of(Row.line("=SUM(A1:A9)", new BigDecimal("10"), null)));
        assertThat(csv).contains("'=SUM(A1:A9)");
        assertThat(csv.lines().anyMatch(l -> l.startsWith("=SUM"))).isFalse();
    }

    @Test
    void negativeAmount_isNotMangled() {
        String csv = render("Acme Co", List.of(Row.line("Net loss", new BigDecimal("-500"), null)));
        // The amount keeps its leading '-' (numbers are not formula-guarded) and is not quoted.
        assertThat(csv).contains("Net loss,-500,");
        assertThat(csv).doesNotContain("'-500").doesNotContain("\"-500\"");
    }

    @Test
    void labelWithComma_isQuotedExactlyOnce() {
        String csv = render("Acme Co", List.of(Row.line("Rent, office", new BigDecimal("10"), null)));
        // Single-escaped: "Rent, office" — NOT the double-escaped """Rent, office""".
        assertThat(csv).contains("\"Rent, office\",10,");
        assertThat(csv).doesNotContain("\"\"\"Rent");
    }

    @Test
    void plainContent_rendersUnquoted() {
        String csv = render("Acme Co", List.of(Row.line("Cash", new BigDecimal("1000"), new BigDecimal("900"))));
        assertThat(csv).contains("Cash,1000,900");
        assertThat(csv).contains("Balance Sheet");
        assertThat(csv).contains("Acme Co");
    }
}
