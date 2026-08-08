package com.erp.api;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.stock.domain.dto.StockMovementDetailRowDto;
import com.erp.modules.stock.domain.dto.StockMovementReportDto;
import com.erp.modules.stock.domain.dto.StockMovementReportFiltersDto;
import com.erp.modules.stock.domain.dto.StockMovementReportTotalsDto;
import com.erp.modules.stock.domain.dto.StockMovementSummaryRowDto;
import com.erp.modules.stock.domain.enums.StockMovementReportMode;
import com.erp.modules.stock.service.StockMovementReportQuery;
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
 * Period Stock Movement report (K9) — the answer to "show me stock movement for a date range".
 *
 * <p>What ships today as the "Stock Report" ({@link StockReportController}) is a present-moment
 * snapshot with no date range at all; this one is genuinely period-windowed, with OPENING,
 * PURCHASES, SALES, ADJUSTMENTS/OTHER and CLOSING, in SUMMARY (per product) or DETAIL (per
 * movement) form. All aggregation, tenant scoping and filter resolution lives in
 * {@link StockMovementReportQuery}; this controller only binds params and flattens for export.
 *
 * <p>Permission gates reuse the already-seeded codes — {@code INVENTORY.VALUATION.VIEW} to read (the
 * same gate as the other stock registers). The export requires that SAME view gate <em>and</em>
 * {@code REPORT.EXPORT}: a download is a strictly broader disclosure than one on-screen page, so it
 * must never be reachable by someone the on-screen report refuses. Gating the download on
 * {@code REPORT.EXPORT} alone inverted that (a caller holding only the export permission — e.g. the
 * seeded ACCOUNTANT role — was refused the report but handed the whole thing as a file).
 */
@RestController
@RequestMapping("/api/v1/reports/stock-movement")
public class StockMovementReportController {

    private final StockMovementReportQuery reportQuery;
    private final TabularExporter          exporter;

    public StockMovementReportController(StockMovementReportQuery reportQuery,
                                          TabularExporter exporter) {
        this.reportQuery = reportQuery;
        this.exporter    = exporter;
    }

    /**
     * GET /api/v1/reports/stock-movement — one page of the report.
     *
     * <p>Paged: the movement ledger of a busy branch is far too large to return whole. {@code size}
     * is clamped server-side to {@link StockMovementReportQuery#MAX_PAGE_SIZE}.
     */
    @GetMapping
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW')")
    public StockMovementReportDto stockMovementReport(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "SUMMARY") StockMovementReportMode mode,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String productUid,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        Long companyId = RequestContext.get().companyId();
        return reportQuery.report(
                companyId,
                new StockMovementReportFiltersDto(fromDate, toDate, mode, branchUid, productUid),
                page, size);
    }

    /**
     * GET /api/v1/reports/stock-movement/export — the WHOLE report as PDF / XLSX / CSV.
     *
     * <p>Not page-limited (a one-page export is useless), but bounded: past
     * {@link StockMovementReportQuery#MAX_EXPORT_ROWS} the request is refused with a friendly ask to
     * narrow the range.
     */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportStockMovementReport(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "SUMMARY") StockMovementReportMode mode,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String productUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = RequestContext.get().companyId();
        StockMovementReportDto dto = reportQuery.reportForExport(
                companyId,
                new StockMovementReportFiltersDto(fromDate, toDate, mode, branchUid, productUid));
        return download(exporter.export(flatten(dto), format));
    }

    // -------------------------------------------------------------------------
    // Export flattening
    // -------------------------------------------------------------------------

    private TabularRenderModel flatten(StockMovementReportDto dto) {
        return dto.mode() == StockMovementReportMode.DETAIL ? flattenDetail(dto) : flattenSummary(dto);
    }

    private TabularRenderModel flattenSummary(StockMovementReportDto dto) {
        List<Column> columns = List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Unit", Align.LEFT),
                new Column("Opening", Align.RIGHT),
                new Column("Purchases", Align.RIGHT),
                new Column("Sales", Align.RIGHT),
                new Column("Adj / Other", Align.RIGHT),
                new Column("Closing", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.summaryRows().size());
        for (StockMovementSummaryRowDto r : dto.summaryRows()) {
            rows.add(List.of(
                    nullToEmpty(r.productCode()),
                    nullToEmpty(r.productName()),
                    nullToEmpty(r.unitLabel()),
                    fmtQty(r.openingQty()),
                    fmtQty(r.purchasesIn()),
                    fmtQty(r.salesOut()),
                    fmtQty(r.adjustmentsOther()),
                    fmtQty(r.closingQty())));
        }

        StockMovementReportTotalsDto t = dto.totals();
        List<String> totalsRow = List.of(
                "", "TOTAL", "",
                fmtQty(t.openingQty()),
                fmtQty(t.purchasesIn()),
                fmtQty(t.salesOut()),
                fmtQty(t.adjustmentsOther()),
                fmtQty(t.closingQty()));

        return new TabularRenderModel("Stock Movement Report", headerLines(dto), dto.generatedAt(),
                columns, rows, totalsRow);
    }

    private TabularRenderModel flattenDetail(StockMovementReportDto dto) {
        List<Column> columns = List.of(
                new Column("Date", Align.LEFT),
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Unit", Align.LEFT),
                new Column("Type", Align.LEFT),
                new Column("Dir", Align.LEFT),
                new Column("Qty", Align.RIGHT),
                new Column("Balance", Align.RIGHT),
                new Column("Reason", Align.LEFT),
                new Column("Source", Align.LEFT));

        List<List<String>> rows = new ArrayList<>(dto.detailRows().size());
        for (StockMovementDetailRowDto r : dto.detailRows()) {
            rows.add(List.of(
                    fmtDateTime(r.occurredAt()),
                    nullToEmpty(r.productCode()),
                    nullToEmpty(r.productName()),
                    nullToEmpty(r.unitLabel()),
                    humanise(r.movementType()),
                    nullToEmpty(r.direction()),
                    fmtQty(r.quantity()),
                    fmtQty(r.runningBalance()),
                    humanise(r.reason()),
                    sourceLabel(r)));
        }

        // The detail grid has no per-column sum that means anything except the period's net
        // movement (closing − opening) and the closing balance itself — show exactly those two.
        StockMovementReportTotalsDto t = dto.totals();
        BigDecimal netMovement = t.closingQty().subtract(t.openingQty());
        List<String> totalsRow = List.of(
                "TOTAL", "", "", "", "", "",
                fmtQty(netMovement),
                fmtQty(t.closingQty()),
                "", "");

        return new TabularRenderModel("Stock Movement Detail", headerLines(dto), dto.generatedAt(),
                columns, rows, totalsRow);
    }

    private List<String> headerLines(StockMovementReportDto dto) {
        List<String> headerLines = new ArrayList<>();
        ReportCompanyHeaderDto company = dto.company();
        if (company != null) {
            if (company.name() != null) {
                headerLines.add(company.name());
            }
            String address = joinAddress(company);
            if (address != null) {
                headerLines.add(address);
            }
            if (company.contactPhone() != null) {
                headerLines.add("Tel: " + company.contactPhone());
            }
            if (company.contactEmail() != null) {
                headerLines.add("Email: " + company.contactEmail());
            }
            if (company.taxId() != null) {
                headerLines.add("TIN: " + company.taxId());
            }
            if (company.vrn() != null) {
                headerLines.add("VRN: " + company.vrn());
            }
        }
        headerLines.add("From " + dto.fromDate() + " To " + dto.toDate());
        if (dto.branchName() != null) {
            headerLines.add("Branch: " + dto.branchName());
        }
        if (dto.productLabel() != null) {
            headerLines.add("Product: " + dto.productLabel());
        }
        // Spelled out on the page so a printed copy is self-describing: without it a reader cannot
        // tell why Opening + Purchases - Sales does not equal Closing. ASCII only — the PDF renderer
        // uses Helvetica with the default encoding, which drops a U+2212 minus sign.
        headerLines.add("Closing = Opening + Purchases - Sales + Adjustments / Other "
                + "(transfers, counts, imports, production and project issues)");
        headerLines.add("Quantities are in each product's base unit");
        return headerLines;
    }

    // -------------------------------------------------------------------------
    // Formatting helpers
    // -------------------------------------------------------------------------

    private String joinAddress(ReportCompanyHeaderDto c) {
        StringBuilder sb = new StringBuilder();
        appendPart(sb, c.addressLine1());
        appendPart(sb, c.addressLine2());
        appendPart(sb, c.city());
        appendPart(sb, c.region());
        appendPart(sb, c.country());
        return sb.length() == 0 ? null : sb.toString();
    }

    private void appendPart(StringBuilder sb, String part) {
        if (part == null || part.isBlank()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append(", ");
        }
        sb.append(part);
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    /**
     * {@code GOODS_RECEIPT} → {@code Goods receipt}. Only SCREAMING_SNAKE codes are rewritten;
     * free-text (an operator's note in the reason column) is passed through untouched so its own
     * capitalisation survives.
     */
    private String humanise(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        if (!text.equals(text.toUpperCase())) {
            return text;
        }
        String spaced = text.replace('_', ' ').toLowerCase();
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }

    private String sourceLabel(StockMovementDetailRowDto r) {
        if (r.sourceDocumentType() == null) {
            return "";
        }
        String type = humanise(r.sourceDocumentType());
        return r.sourceDocumentUid() != null ? type + " " + r.sourceDocumentUid() : type;
    }

    /** {@code 2026-06-30T14:22:31+03:00} → {@code 2026-06-30 14:22}. */
    private String fmtDateTime(String isoOffsetDateTime) {
        if (isoOffsetDateTime == null || isoOffsetDateTime.length() < 16) {
            return nullToEmpty(isoOffsetDateTime);
        }
        return isoOffsetDateTime.substring(0, 10) + " " + isoOffsetDateTime.substring(11, 16);
    }

    /**
     * Whole quantities print without decimals; fractional ones (weighed goods, litres) keep 3 dp so
     * a 0.250 kg movement does not render as "0".
     */
    private String fmtQty(BigDecimal qty) {
        if (qty == null) {
            return "";
        }
        return qty.stripTrailingZeros().scale() <= 0
                ? String.format("%,.0f", qty)
                : String.format("%,.3f", qty);
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
