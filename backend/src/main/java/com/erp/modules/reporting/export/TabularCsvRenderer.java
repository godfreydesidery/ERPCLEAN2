package com.erp.modules.reporting.export;

import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * CSV renderer for {@link TabularRenderModel} — RFC-4180 quoting, no external dependency.
 * Sibling of {@link CsvStatementRenderer} (the fixed 3-column financial shape); this one handles an
 * arbitrary number of columns.
 */
@Component
public class TabularCsvRenderer {

    public byte[] render(TabularRenderModel model) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            w.println(text(model.title()));
            if (model.headerLines() != null) {
                for (String line : model.headerLines()) {
                    w.println(text(line));
                }
            }
            w.println(join(text("Generated"), text(model.generatedAt())));
            w.println();

            w.println(joinAll(model.columns().stream().map(TabularRenderModel.Column::header).toList()));

            for (List<String> row : model.rows()) {
                w.println(joinAll(row));
            }

            if (model.totalsRow() != null) {
                w.println(joinAll(model.totalsRow()));
            }
        }
        return baos.toByteArray();
    }

    // -------------------------------------------------------------------------

    private String join(String... cells) {
        return String.join(",", cells);
    }

    private String joinAll(List<String> cells) {
        return String.join(",", cells.stream().map(this::text).toList());
    }

    /** A cell: neutralise CSV/Excel formula injection, then CSV-escape. */
    private String text(String val) {
        return escape(formulaGuard(val));
    }

    /** A plain (possibly negative, thousands-grouped) number — never a formula-injection risk. */
    private static final java.util.regex.Pattern NUMERIC =
            java.util.regex.Pattern.compile("-?[\\d,]+(\\.\\d+)?");

    /**
     * Neutralise CSV/Excel formula injection: a cell starting with = + - @ (or TAB/CR) is treated as
     * a formula by spreadsheet apps. Prefix a single quote so it is rendered as literal text. A
     * leading {@code -} is exempted when the whole cell is a plain negative number (e.g.
     * {@code -1,000.00}) so genuine report figures stay numeric in Excel — only a {@code -} followed
     * by non-numeric content (a real formula like {@code -1+cmd}) is guarded.
     */
    private String formulaGuard(String val) {
        if (val == null || val.isEmpty()) return val;
        char c0 = val.charAt(0);
        boolean trigger = c0 == '=' || c0 == '+' || c0 == '@' || c0 == '\t' || c0 == '\r'
                || (c0 == '-' && !NUMERIC.matcher(val).matches());
        return trigger ? "'" + val : val;
    }

    private String escape(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n") || val.contains("\r")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }
}
