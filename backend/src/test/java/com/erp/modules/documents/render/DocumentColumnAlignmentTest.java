package com.erp.modules.documents.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.documents.render.DocumentPdfRenderer.Col;
import com.erp.modules.documents.render.DocumentPdfRenderer.ColumnPlan;
import com.erp.modules.documents.render.DocumentRenderModel.DocLine;
import com.lowagie.text.Element;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the column-alignment rule the client asked for (K9 follow-up, 2026-08-12):
 * <b>only money is right-aligned</b>, and a heading always takes its own column's alignment.
 *
 * <p>The bug this guards against was purely visual — headings were pinned left while the figures
 * under them were right-aligned, so every number drifted to the far edge of its column and read as
 * belonging to the next one along. Extracted PDF text cannot show that (it carries no positions),
 * so the rule is asserted on the column plan instead, which is the single place both the heading
 * and its values take their alignment from.
 */
class DocumentColumnAlignmentTest {

    /** A GRN line: it earns every column the widest layout has. */
    private static final DocLine GRN_LINE = new DocLine(
            1, "00009945", "KONDIKI MTINDI 5LT", new BigDecimal("20"), "PCS",
            new BigDecimal("13000"), null, null, new BigDecimal("260000"),
            new BigDecimal("15000"), new BigDecimal("13000"), new BigDecimal("13.33"));

    @Test
    void onlyMoneyColumnsAreRightAligned() {
        Map<String, Integer> align = alignmentsOf(
                DocumentPdfRenderer.planColumns(List.of(GRN_LINE), true, true));

        // Text reads left — including the quantity, which is a count, not an amount.
        assertThat(align.get("Code")).isEqualTo(Element.ALIGN_LEFT);
        assertThat(align.get("Product Description")).isEqualTo(Element.ALIGN_LEFT);
        assertThat(align.get("Qty")).isEqualTo(Element.ALIGN_LEFT);
        assertThat(align.get("Unit")).isEqualTo(Element.ALIGN_LEFT);

        // Money reads right, so the decimal points stack down the column.
        assertThat(align.get("CP")).isEqualTo(Element.ALIGN_RIGHT);
        assertThat(align.get("SP")).isEqualTo(Element.ALIGN_RIGHT);
        assertThat(align.get("Last CP")).isEqualTo(Element.ALIGN_RIGHT);
        assertThat(align.get("Amount")).isEqualTo(Element.ALIGN_RIGHT);
        // The margin rides with them: two decimals, sitting between two money columns.
        assertThat(align.get("Mar.(%)")).isEqualTo(Element.ALIGN_RIGHT);
    }

    /**
     * Every value a row emits must land in the column that planned it. A count mismatch would shift
     * each figure one column left for the rest of the row — the failure mode the list-based row
     * builder exists to prevent.
     */
    @Test
    void everyLineEmitsExactlyOneValuePerPlannedColumn() {
        DocumentPdfRenderer renderer = new DocumentPdfRenderer();

        record Case(String name, DocLine line, boolean prices, boolean code) {}
        List<Case> cases = List.of(
                new Case("GRN", GRN_LINE, true, true),
                new Case("invoice line with discount", new DocLine(
                        1, "P1", "Item", new BigDecimal("2"), "EA",
                        new BigDecimal("100"), new BigDecimal("5"), "STANDARD",
                        new BigDecimal("195")), true, false),
                new Case("delivery note (qty only)", new DocLine(
                        1, "P1", "Item", new BigDecimal("2"), "EA",
                        null, null, null, null), false, false));

        for (Case c : cases) {
            ColumnPlan plan = DocumentPdfRenderer.planColumns(List.of(c.line()), c.prices(), c.code());
            assertThat(renderer.rowValues(c.line(), plan))
                    .as("%s: one value per column", c.name())
                    .hasSameSizeAs(plan.cols());
        }
    }

    /** A document with no prices keeps four plain left-aligned columns. */
    @Test
    void aQuantityOnlyDocumentHasNoRightAlignedColumns() {
        ColumnPlan plan = DocumentPdfRenderer.planColumns(
                List.of(new DocLine(1, "P1", "Item", new BigDecimal("2"), "EA",
                        null, null, null, null)),
                false, false);

        assertThat(plan.cols()).hasSize(4);
        assertThat(plan.cols()).allMatch(c -> c.align() == Element.ALIGN_LEFT);
    }

    private static Map<String, Integer> alignmentsOf(ColumnPlan plan) {
        return plan.cols().stream().collect(Collectors.toMap(Col::header, Col::align,
                (a, b) -> a, java.util.LinkedHashMap::new));
    }
}
