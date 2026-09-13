package com.erp.api;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.sales.domain.dto.ProfitabilityReportDto;
import com.erp.modules.sales.domain.dto.ProfitabilityRowDto;
import com.erp.modules.sales.domain.dto.ProfitabilityTotalsDto;
import com.erp.modules.sales.service.ProfitabilityReportQuery;
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
 * Profitability Report (K-2026-08-30 #2) — "a profitability report showing Gross Sales, VAT Amount,
 * Net Amount, Cost of Sales and profit amount", per product with totals at the foot.
 *
 * <p><b>Permission.</b> {@code SALES.INVOICE.VIEW}, and {@code REPORT.EXPORT} additionally for the
 * download — identical to {@link SalesReportController}. That report already discloses margin (net
 * less cost of sale) at this gate, so cost of sales here is the same disclosure to the same
 * audience; a second gate would lock out the manager who asked for the report without protecting
 * anything the Sales Report does not already show them.
 */
@RestController
@RequestMapping("/api/v1/reports/profitability")
public class ProfitabilityReportController {

    private final ProfitabilityReportQuery query;
    private final TabularExporter          exporter;

    public ProfitabilityReportController(ProfitabilityReportQuery query, TabularExporter exporter) {
        this.query    = query;
        this.exporter = exporter;
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public ProfitabilityReportDto profitability(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid) {
        return query.report(RequestContext.get().companyId(), fromDate, toDate, branchUid);
    }

    /**
     * A download discloses strictly more than one screen, so it carries the on-screen gate as well
     * as {@code REPORT.EXPORT} — it must never be reachable by a caller the screen itself refuses.
     */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportProfitability(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        ProfitabilityReportDto dto =
                query.report(RequestContext.get().companyId(), fromDate, toDate, branchUid);
        return download(exporter.export(flatten(dto), format));
    }

    // -------------------------------------------------------------------------

    private TabularRenderModel flatten(ProfitabilityReportDto dto) {
        List<Column> columns = List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Qty Sold", Align.RIGHT),
                new Column("Gross Sales", Align.RIGHT),
                new Column("VAT", Align.RIGHT),
                new Column("Net Sales", Align.RIGHT),
                new Column("Cost of Sales", Align.RIGHT),
                new Column("Profit", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (ProfitabilityRowDto r : dto.rows()) {
            rows.add(List.of(
                    nullToEmpty(r.productCode()),
                    nullToEmpty(r.productName()),
                    fmtQty(r.qtySold()),
                    fmtAmt(r.grossSales()),
                    fmtAmt(r.vatAmount()),
                    fmtAmt(r.netAmount()),
                    fmtAmt(r.costOfSales()),
                    fmtAmt(r.profit())));
        }

        ProfitabilityTotalsDto t = dto.totals();
        List<String> totalsRow = t == null ? null : List.of(
                "", "TOTAL",
                fmtQty(t.qtySold()),
                fmtAmt(t.grossSales()),
                fmtAmt(t.vatAmount()),
                fmtAmt(t.netAmount()),
                fmtAmt(t.costOfSales()),
                fmtAmt(t.profit()));

        return new TabularRenderModel("Profitability Report", headerLines(dto), dto.generatedAt(),
                columns, rows, totalsRow);
    }

    private List<String> headerLines(ProfitabilityReportDto dto) {
        List<String> lines = new ArrayList<>();
        ReportCompanyHeaderDto c = dto.company();
        if (c != null) {
            addIfPresent(lines, c.name());
            String address = joinAddress(c);
            if (address != null) {
                lines.add(address);
            }
            if (c.contactPhone() != null) {
                lines.add("Tel: " + c.contactPhone());
            }
            if (c.contactEmail() != null) {
                lines.add("Email: " + c.contactEmail());
            }
            if (c.taxId() != null) {
                lines.add("TIN: " + c.taxId());
            }
            if (c.vrn() != null) {
                lines.add("VRN: " + c.vrn());
            }
        }
        lines.add("From " + dto.fromDate() + " To " + dto.toDate());
        lines.add("Branch: " + (dto.branchName() != null
                ? dto.branchName()
                : "All branches (whole company)"));

        // What the totals leave out, printed on the page itself. Without this line a foot that
        // omits some cost of sales reads as complete and overstates the profit.
        ProfitabilityTotalsDto t = dto.totals();
        if (t != null && t.rowsWithUnknownCost() > 0) {
            lines.add("Excluded from Cost of Sales and Profit: " + t.rowsWithUnknownCost()
                    + " item(s) sold before their stock had ever been costed");
        }
        return lines;
    }

    private void addIfPresent(List<String> lines, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(value);
        }
    }

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

    /** Blank, never "0.00" — an unknown cost of sales is not a free one. */
    private String fmtAmt(BigDecimal amt) {
        return amt != null ? String.format("%,.2f", amt) : "";
    }

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
