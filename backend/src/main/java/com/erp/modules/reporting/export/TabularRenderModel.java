package com.erp.modules.reporting.export;

import java.util.List;

/**
 * Statement-agnostic flat render model for N-column tabular reports (SAM Electronix go-live
 * reporting). Sibling of {@link StatementRenderModel} — that one is a fixed 3-column
 * (label/current/comparative) financial-statement shape; this one is a generic tabular register
 * (Sales Report, Stock Report, and future list-style exports) with an arbitrary number of columns.
 *
 * <p>Cells arrive PRE-FORMATTED as strings — no {@code BigDecimal} formatting happens in the
 * renderers; the caller (controller) decides the number format (thousands separators, decimal
 * places) before building the model.
 */
public record TabularRenderModel(
        String             title,
        List<String>       headerLines,
        String             generatedAt,
        List<Column>       columns,
        List<List<String>> rows,
        List<String>       totalsRow
) {

    public enum Align { LEFT, RIGHT, CENTER }

    public record Column(String header, Align align) {}
}
