package com.erp.modules.reporting.export;

import java.math.BigDecimal;
import java.util.List;

/**
 * Statement-agnostic flat render model consumed by all export renderers (ADR-0018 D-9).
 * Every statement DTO is flattened to a header + ordered rows before export.
 */
public record StatementRenderModel(
        String      title,
        String      companyName,
        String      currency,
        String      periodLabel,
        String      comparativeLabel,
        String      generatedAt,
        List<Row>   rows
) {

    public enum RowType { SECTION_HEADER, LINE, SUBTOTAL, TOTAL, RECONCILIATION }

    public record Row(
            RowType    rowType,
            String     label,
            BigDecimal current,
            BigDecimal comparative,
            boolean    reconciliationBar,
            boolean    ties
    ) {
        /** Convenience for a regular line row. */
        public static Row line(String label, BigDecimal current, BigDecimal comparative) {
            return new Row(RowType.LINE, label, current, comparative, false, false);
        }

        public static Row sectionHeader(String label) {
            return new Row(RowType.SECTION_HEADER, label, null, null, false, false);
        }

        public static Row subtotal(String label, BigDecimal current, BigDecimal comparative) {
            return new Row(RowType.SUBTOTAL, label, current, comparative, false, false);
        }

        public static Row total(String label, BigDecimal current, BigDecimal comparative) {
            return new Row(RowType.TOTAL, label, current, comparative, false, false);
        }

        public static Row reconciliation(String label, BigDecimal difference, boolean ties) {
            return new Row(RowType.RECONCILIATION, label, difference, null, true, ties);
        }
    }
}
