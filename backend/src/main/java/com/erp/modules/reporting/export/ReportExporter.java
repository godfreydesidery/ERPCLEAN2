package com.erp.modules.reporting.export;

import com.erp.modules.reporting.domain.enums.ExportFormat;
import org.springframework.stereotype.Component;

/**
 * Facade: (StatementRenderModel, ExportFormat) → ExportResult (ADR-0018 D-9).
 * Callers build the render model via {@link StatementModelFlattener}, then call this.
 */
@Component
public class ReportExporter {

    private static final String MIME_PDF  = "application/pdf";
    private static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MIME_CSV  = "text/csv";

    private final PdfStatementRenderer  pdfRenderer;
    private final XlsxStatementRenderer xlsxRenderer;
    private final CsvStatementRenderer  csvRenderer;

    public ReportExporter(PdfStatementRenderer pdfRenderer,
                           XlsxStatementRenderer xlsxRenderer,
                           CsvStatementRenderer csvRenderer) {
        this.pdfRenderer  = pdfRenderer;
        this.xlsxRenderer = xlsxRenderer;
        this.csvRenderer  = csvRenderer;
    }

    /**
     * Exports the given render model to the requested format.
     * Returns an {@link ExportResult} carrying the bytes, MIME type, and suggested filename.
     */
    public ExportResult export(StatementRenderModel model, ExportFormat format) {
        String slug = slugify(model.title());
        return switch (format) {
            case PDF  -> new ExportResult(
                    pdfRenderer.render(model), MIME_PDF,  slug + ".pdf");
            case XLSX -> new ExportResult(
                    xlsxRenderer.render(model), MIME_XLSX, slug + ".xlsx");
            case CSV  -> new ExportResult(
                    csvRenderer.render(model), MIME_CSV,  slug + ".csv");
        };
    }

    private String slugify(String title) {
        if (title == null) return "report";
        return title.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("-+$", "");
    }
}
