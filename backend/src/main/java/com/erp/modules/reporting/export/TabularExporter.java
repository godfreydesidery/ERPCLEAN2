package com.erp.modules.reporting.export;

import com.erp.modules.reporting.domain.enums.ExportFormat;
import org.springframework.stereotype.Component;

/**
 * Facade: (TabularRenderModel, ExportFormat) -&gt; ExportResult. Sibling of {@link ReportExporter}
 * (the fixed 3-column financial-statement facade) for the generic N-column tabular path.
 */
@Component
public class TabularExporter {

    private static final String MIME_PDF  = "application/pdf";
    private static final String MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final String MIME_CSV  = "text/csv";

    private final TabularPdfRenderer  pdfRenderer;
    private final TabularXlsxRenderer xlsxRenderer;
    private final TabularCsvRenderer  csvRenderer;

    public TabularExporter(TabularPdfRenderer pdfRenderer,
                            TabularXlsxRenderer xlsxRenderer,
                            TabularCsvRenderer csvRenderer) {
        this.pdfRenderer  = pdfRenderer;
        this.xlsxRenderer = xlsxRenderer;
        this.csvRenderer  = csvRenderer;
    }

    /**
     * Exports the given render model to the requested format.
     * Returns an {@link ExportResult} carrying the bytes, MIME type, and suggested filename.
     */
    public ExportResult export(TabularRenderModel model, ExportFormat format) {
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
