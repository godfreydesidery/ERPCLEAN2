package com.erp.modules.reporting.export;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests for {@link TabularExporter} — filename/MIME type resolution per {@link
 * ExportFormat}, mirroring the fixed-shape {@code ReportExporter}'s own contract.
 */
class TabularExporterTest {

    private final TabularExporter exporter = new TabularExporter(
            new TabularPdfRenderer(), new TabularXlsxRenderer(), new TabularCsvRenderer());

    private TabularRenderModel model() {
        return new TabularRenderModel(
                "Sales Report",
                List.of("Acme Ltd"),
                "2026-07-19T10:00:00Z",
                List.of(new Column("Code", Align.LEFT), new Column("Amount", Align.RIGHT)),
                List.of(List.of("P001", "100.00")),
                List.of("TOTAL", "100.00"));
    }

    @Test
    void pdf_hasPdfMimeAndFilename() {
        var result = exporter.export(model(), ExportFormat.PDF);
        assertThat(result.contentType()).isEqualTo("application/pdf");
        assertThat(result.filename()).isEqualTo("sales-report.pdf");
        assertThat(result.content()).isNotEmpty();
    }

    @Test
    void xlsx_hasXlsxMimeAndFilename() {
        var result = exporter.export(model(), ExportFormat.XLSX);
        assertThat(result.contentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
        assertThat(result.filename()).isEqualTo("sales-report.xlsx");
        assertThat(result.content()).isNotEmpty();
    }

    @Test
    void csv_hasCsvMimeAndFilename() {
        var result = exporter.export(model(), ExportFormat.CSV);
        assertThat(result.contentType()).isEqualTo("text/csv");
        assertThat(result.filename()).isEqualTo("sales-report.csv");
        assertThat(result.content()).isNotEmpty();
    }

    @Test
    void titleIsSlugified_forFilename() {
        TabularRenderModel weirdTitle = new TabularRenderModel(
                "Stock Report: Branch A / Q3!!",
                List.of(),
                "2026-07-19T10:00:00Z",
                List.of(new Column("Code", Align.LEFT)),
                List.of(),
                null);
        var result = exporter.export(weirdTitle, ExportFormat.CSV);
        assertThat(result.filename()).isEqualTo("stock-report-branch-a-q3.csv");
    }
}
