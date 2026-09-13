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
 *
 * @param logoDataUri the company's logo as a base64 data URI, drawn above the title in the PDF
 *                    (the spreadsheet and CSV forms have nowhere sensible to put it). Null when the
 *                    company has not uploaded one — the document then prints text-only, exactly as
 *                    it did before, rather than failing.
 * @param footerLines lines printed BELOW the table in every format — the print footprint ("Printed
 *                    On / At / By"), sign-off rules, and anything else that belongs at the foot of
 *                    a document rather than its head. Never null; empty for a report that wants
 *                    none, which is what the six-argument constructor gives existing callers.
 */
public record TabularRenderModel(
        String             title,
        List<String>       headerLines,
        String             generatedAt,
        List<Column>       columns,
        List<List<String>> rows,
        List<String>       totalsRow,
        List<String>       footerLines,
        String             logoDataUri
) {

    /** Normalises {@code footerLines} so no renderer has to null-check it. */
    public TabularRenderModel {
        footerLines = footerLines != null ? List.copyOf(footerLines) : List.of();
    }

    /** Footer but no logo. */
    public TabularRenderModel(String title, List<String> headerLines, String generatedAt,
                              List<Column> columns, List<List<String>> rows,
                              List<String> totalsRow, List<String> footerLines) {
        this(title, headerLines, generatedAt, columns, rows, totalsRow, footerLines, null);
    }

    /**
     * The shape every report used before documents needed a foot. Kept so the twelve callers that
     * want no footer say so by not mentioning one, rather than each passing an empty list.
     */
    public TabularRenderModel(String title, List<String> headerLines, String generatedAt,
                              List<Column> columns, List<List<String>> rows,
                              List<String> totalsRow) {
        this(title, headerLines, generatedAt, columns, rows, totalsRow, List.of(), null);
    }

    public enum Align { LEFT, RIGHT, CENTER }

    public record Column(String header, Align align) {}
}
