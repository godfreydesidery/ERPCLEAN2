package com.erp.modules.documents.render;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.documents.render.DocumentPdfRenderer.Col;
import com.erp.modules.documents.render.DocumentPdfRenderer.ColumnPlan;
import com.erp.modules.documents.render.DocumentRenderModel.DocLine;
import com.lowagie.text.Element;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Pins the table-alignment rule the owner settled on (Kilimanjaro, 2026-08-12):
 *
 * <ul>
 *   <li><b>Only money VALUES are right-aligned</b> — cost, selling price, previous cost, discount
 *       and the line amount, so the decimal points stack and the column reads down. A quantity is a
 *       count, not an amount, and reads left with the text columns.</li>
 *   <li><b>Headings always read left</b>, money columns included: a heading is a column NAME, not a
 *       figure.</li>
 * </ul>
 *
 * <p>Extracted PDF text carries no positions, so the rule is asserted where the cells actually take
 * it from — the column plan and {@code HEADING_ALIGN} — rather than by parsing a rendered page.
 */
class DocumentColumnAlignmentTest {

    /** A GRN line: it earns every column the widest layout has. */
    private static final DocLine GRN_LINE = new DocLine(
            1, "00009945", "KONDIKI MTINDI 5LT", new BigDecimal("20"), "PCS",
            new BigDecimal("13000"), null, null, new BigDecimal("260000"),
            new BigDecimal("15000"), new BigDecimal("13000"), new BigDecimal("13.33"));

    @Test
    void onlyMoneyValuesAreRightAligned() {
        Map<String, Integer> align = valueAlignmentsOf(
                DocumentPdfRenderer.planColumns(List.of(GRN_LINE), true, true));

        // Text reads left — including the quantity, which is a count, not an amount.
        assertThat(align)
                .containsEntry("Code", Element.ALIGN_LEFT)
                .containsEntry("Product Description", Element.ALIGN_LEFT)
                .containsEntry("Qty", Element.ALIGN_LEFT)
                .containsEntry("Unit", Element.ALIGN_LEFT);

        // Money reads right, so the decimal points stack. The margin rides with them: two decimals,
        // sitting between two money columns.
        assertThat(align)
                .containsEntry("CP", Element.ALIGN_RIGHT)
                .containsEntry("SP", Element.ALIGN_RIGHT)
                .containsEntry("Last CP", Element.ALIGN_RIGHT)
                .containsEntry("Mar.(%)", Element.ALIGN_RIGHT)
                .containsEntry("Amount", Element.ALIGN_RIGHT);
    }

    /**
     * A heading is a column name, so it reads left over every column — money included, where it
     * therefore DIFFERS from the values beneath it. That divergence is the deliberate part: making
     * the heading match its figures is a strong instinct, it was tried once, and it was rejected.
     */
    @Test
    void headingsReadLeftEvenOverRightAlignedMoneyColumns() {
        assertThat(DocumentPdfRenderer.HEADING_ALIGN).isEqualTo(Element.ALIGN_LEFT);

        ColumnPlan plan = DocumentPdfRenderer.planColumns(List.of(GRN_LINE), true, true);
        Col amount = plan.cols().stream()
                .filter(c -> "Amount".equals(c.header()))
                .findFirst().orElseThrow();

        assertThat(amount.valueAlign()).isEqualTo(Element.ALIGN_RIGHT);
        assertThat(DocumentPdfRenderer.HEADING_ALIGN).isNotEqualTo(amount.valueAlign());
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

    /** A document with no prices has nothing to right-align. */
    @Test
    void aQuantityOnlyDocumentHasNoRightAlignedValues() {
        ColumnPlan plan = DocumentPdfRenderer.planColumns(
                List.of(new DocLine(1, "P1", "Item", new BigDecimal("2"), "EA",
                        null, null, null, null)),
                false, false);

        assertThat(plan.cols()).hasSize(4);
        assertThat(plan.cols()).allMatch(c -> c.valueAlign() == Element.ALIGN_LEFT);
    }

    private static Map<String, Integer> valueAlignmentsOf(ColumnPlan plan) {
        return plan.cols().stream().collect(Collectors.toMap(
                Col::header, Col::valueAlign, (a, b) -> a, LinkedHashMap::new));
    }
}
