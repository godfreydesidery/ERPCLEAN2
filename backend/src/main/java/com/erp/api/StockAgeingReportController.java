package com.erp.api;

import static com.erp.modules.reporting.export.ReportExportFormat.amount;
import static com.erp.modules.reporting.export.ReportExportFormat.quantity;
import static com.erp.modules.reporting.export.ReportExportFormat.text;

import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.ReportExportFormat;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.stock.domain.dto.StockAgeingReportDto;
import com.erp.modules.stock.domain.dto.StockAgeingRowDto;
import com.erp.modules.stock.service.StockAgeingReportQuery;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.format.annotation.DateTimeFormat.ISO;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock Ageing — on-hand quantity and value per product in age buckets (assuming first-in,
 * first-out), plus days since last sale.
 *
 * <p>Gated {@code INVENTORY.VALUATION.VIEW}, like every other report that prints stock value. The
 * FIFO assumption is printed on the page, because an age that is inferred must say so.
 */
@RestController
@RequestMapping("/api/v1/reports/stock-ageing")
public class StockAgeingReportController {

    /** Printed under the title of every export; the screen carries the same sentence. */
    static final String FIFO_NOTE = "Ages assume first-in, first-out: the stock on hand is taken "
            + "to be the most recently received units. Stock is costed at moving average, so each "
            + "bucket is valued at the item's average cost - ageing is a view of quantities.";

    private final StockAgeingReportQuery query;
    private final TabularExporter        exporter;

    public StockAgeingReportController(StockAgeingReportQuery query, TabularExporter exporter) {
        this.query    = query;
        this.exporter = exporter;
    }

    @GetMapping
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW')")
    public StockAgeingReportDto stockAgeing(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String branchUid) {
        return query.report(RequestContext.get().companyId(), asOf, branchUid);
    }

    /** On-screen gate AND {@code REPORT.EXPORT}, as on every report download. */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportStockAgeing(
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate asOf,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        StockAgeingReportDto dto = query.report(RequestContext.get().companyId(), asOf, branchUid);
        return download(exporter.export(flatten(dto), format));
    }

    // -------------------------------------------------------------------------

    private TabularRenderModel flatten(StockAgeingReportDto dto) {
        List<String> headerLines = new ArrayList<>(ReportExportFormat.companyLines(dto.company()));
        headerLines.add("As of " + dto.asOf());
        headerLines.add("Branch: " + (dto.branchName() != null
                ? dto.branchName() : "All branches (whole company)"));
        headerLines.add(FIFO_NOTE);
        if (dto.valuedAtCurrentCost()) {
            headerLines.add("Quantities are as of " + dto.asOf()
                    + "; values use today's average cost.");
        }
        if (dto.uncoveredProducts() > 0) {
            headerLines.add(dto.uncoveredProducts() + " item(s) hold stock older than their "
                    + "recorded movements; that stock is shown as Over 180 days (marked *).");
        }
        if (dto.unvaluedProducts() > 0) {
            headerLines.add("Not in the value totals: " + dto.unvaluedProducts()
                    + " item(s) with no cost on record.");
        }
        if (dto.negativeStockProducts() > 0) {
            headerLines.add("Left out: " + dto.negativeStockProducts()
                    + " item(s) with negative stock, which has no age.");
        }

        List<Column> columns = new ArrayList<>(List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("On Hand", Align.RIGHT),
                new Column("Value", Align.RIGHT)));
        for (String b : dto.buckets()) {
            columns.add(new Column(b, Align.RIGHT));
        }
        columns.add(new Column("Last Sold", Align.LEFT));
        columns.add(new Column("Days Since Sale", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        int oldest = dto.buckets().size() - 1;
        for (StockAgeingRowDto r : dto.rows()) {
            List<String> line = new ArrayList<>(List.of(
                    text(r.productCode()),
                    text(r.productName()),
                    quantity(r.onHand()),
                    amount(r.value())));
            for (int i = 0; i < r.bucketQty().size(); i++) {
                String cell = quantity(r.bucketQty().get(i));
                if (i == oldest && r.uncoveredQty().signum() > 0) {
                    cell = cell + " *";
                }
                line.add(cell);
            }
            line.add(r.lastSaleDate() != null ? r.lastSaleDate() : "Never");
            line.add(r.daysSinceLastSale() != null ? String.valueOf(r.daysSinceLastSale()) : "");
            rows.add(line);
        }

        List<String> totalsRow = new ArrayList<>(List.of(
                "", "TOTAL", quantity(dto.totalOnHand()), amount(dto.totalValue())));
        for (BigDecimal q : dto.totalBucketQty()) {
            totalsRow.add(quantity(q));
        }
        totalsRow.add("");
        totalsRow.add("");

        // Values per bucket, as a second foot line, so a reader of the printout can see what the
        // old stock is worth — not only how much of it there is.
        List<String> valueLine = new ArrayList<>(List.of("", "VALUE", "", ""));
        for (BigDecimal v : dto.totalBucketValue()) {
            valueLine.add(amount(v));
        }
        valueLine.add("");
        valueLine.add("");
        rows.add(valueLine);

        return new TabularRenderModel("Stock Ageing", headerLines, dto.generatedAt(),
                columns, rows, totalsRow);
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
