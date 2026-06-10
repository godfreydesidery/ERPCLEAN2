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
            w.println(csvRow(model.title(), "", ""));
            w.println(csvRow(model.companyName(), "", ""));
            w.println(csvRow(model.periodLabel(), model.comparativeLabel() != null ? model.comparativeLabel() : "", ""));
            w.println(csvRow("Generated", model.generatedAt(), ""));
            w.println();
            w.println(csvRow("Description", "Current", "Comparative"));
            for (Row row : model.rows()) {
                w.println(csvRow(
                        escape(row.label()),
                        fmtAmount(row.current()),
                        fmtAmount(row.comparative())));
            }
        }
        return baos.toByteArray();
    }

    private String csvRow(String a, String b, String c) {
        return escape(a) + "," + escape(b) + "," + escape(c);
    }

    private String escape(String val) {
        if (val == null) return "";
        if (val.contains(",") || val.contains("\"") || val.contains("\n")) {
            return "\"" + val.replace("\"", "\"\"") + "\"";
        }
        return val;
    }

    private String fmtAmount(BigDecimal amt) {
        return amt != null ? amt.toPlainString() : "";
    }
}
