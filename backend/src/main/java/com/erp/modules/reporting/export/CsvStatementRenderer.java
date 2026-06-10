package com.erp.modules.reporting.export;

import com.erp.modules.reporting.export.StatementRenderModel.Row;
import java.io.ByteArrayOutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import org.springframework.stereotype.Component;

/**
 * CSV renderer — no external dependency; plain comma-delimited output (ADR-0018 D-9).
 */
@Component
public class CsvStatementRenderer {

    public byte[] render(StatementRenderModel model) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter w = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8))) {
            w.println(line(text(model.title())));
            w.println(line(text(model.companyName())));
            w.println(line(text(model.periodLabel()),
                            text(model.comparativeLabel() != null ? model.comparativeLabel() : "")));
            w.println(line(text("Generated"), text(model.generatedAt())));
            w.println();
            w.println(line(text("Description"), text("Current"), text("Comparative")));
            for (Row row : model.rows()) {
                // Labels may be user-controlled (account/company names) → formula-guarded text;
                // amounts are BigDecimal numerics → plain CSV-escape only (don't mangle negatives).
                w.println(line(text(row.label()),
                               escape(fmtAmount(row.current())),
                               escape(fmtAmount(row.comparative()))));
            }
        }
        return baos.toByteArray();
    }

    /** Join already-escaped cells into one CSV record. */
    private String line(String... cells) {
        return String.join(",", cells);
    }

    /** A user/text cell: neutralise CSV formula injection, then CSV-escape. */
    private String text(String val) {
        return escape(formulaGuard(val));
    }

    /**
     * Neutralise CSV/Excel formula injection: a cell starting with = + - @ (or TAB/CR) is treated as
     * a formula by spreadsheet apps. Prefix a single quote so it is rendered as literal text. Applied
     * to text cells only (labels/headers, which may carry user-controlled account/company names);
     * numeric amounts are not guarded so negative values keep their leading '-'.
     */
    private String formulaGuard(String val) {
        if (val == null || val.isEmpty()) return val;
        char c0 = val.charAt(0);
        if (c0 == '=' || c0 == '+' || c0 == '-' || c0 == '@' || c0 == '\t' || c0 == '\r') {
            return "'" + val;
        }
        return val;
    }

    private String escape(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n") || val.contains("\r")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }

    private String fmtAmount(BigDecimal amt) {
        return amt != null ? amt.toPlainString() : "";
    }
}
