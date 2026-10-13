package com.erp.api;

import static com.erp.modules.reporting.export.ReportExportFormat.amount;
import static com.erp.modules.reporting.export.ReportExportFormat.percent;
import static com.erp.modules.reporting.export.ReportExportFormat.quantity;
import static com.erp.modules.reporting.export.ReportExportFormat.text;

import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.ReportExportFormat;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.sales.domain.dto.SalesSummaryReportDto;
import com.erp.modules.sales.domain.dto.SalesSummaryRowDto;
import com.erp.modules.sales.domain.dto.SalesSummaryTotalsDto;
import com.erp.modules.sales.domain.enums.SalesSummaryGroupBy;
import com.erp.modules.sales.service.SalesSummaryReportQuery;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.PermissionChecks;
import com.erp.platform.security.RequestContext;
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
 * Sales Summary — finalised sales grouped by customer, agent, route, branch, day or cashier, with
 * cost of sales and margin. One screen answers "sales by customer", "agent performance", "sales by
 * route", "daily sales" and "cashier sales".
 *
 * <p>Gated {@code SALES.INVOICE.VIEW}, the same as the Sales Report and the Profitability Report:
 * both already disclose margin at that gate, so a grouped view of the same figures is no wider a
 * disclosure. Figures and their rules live in {@link SalesSummaryReportQuery}; this controller only
 * flattens them for export.
 */
@RestController
@RequestMapping("/api/v1/reports/sales-summary")
public class SalesSummaryReportController {

    private final SalesSummaryReportQuery query;
    private final TabularExporter         exporter;
    private final PermissionChecks        perm;
    private final BranchReadGuard         branchGuard;

    public SalesSummaryReportController(SalesSummaryReportQuery query, TabularExporter exporter,
                                        PermissionChecks perm, BranchReadGuard branchGuard) {
        this.query       = query;
        this.exporter    = exporter;
        this.perm        = perm;
        this.branchGuard = branchGuard;
    }

    /**
     * Counter staff (SALES.INVOICE.VIEW without INVENTORY.VALUATION.VIEW) keep the summary but
     * not its cost of sales, margin or margin % (owner ruling 2026-10-10, ADM-14).
     */
    private SalesSummaryReportDto visibleTo(SalesSummaryReportDto dto) {
        return perm.has(SalesReportController.COST_PERMISSION) ? dto : dto.withoutCost();
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public SalesSummaryReportDto salesSummary(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "CUSTOMER") SalesSummaryGroupBy groupBy,
            @RequestParam(required = false) String branchUid) {
        return visibleTo(query.report(
                RequestContext.get().companyId(), fromDate, toDate, groupBy, branchUid));
    }

    /**
     * The export needs the on-screen gate AND {@code REPORT.EXPORT}: a download discloses strictly
     * more than a screen, so it must never reach a caller the screen itself refuses.
     */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportSalesSummary(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "CUSTOMER") SalesSummaryGroupBy groupBy,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = RequestContext.get().companyId();
        SalesSummaryReportDto dto = visibleTo(
                query.report(companyId, fromDate, toDate, groupBy, branchUid));
        return download(exporter.export(
                flatten(dto, branchGuard.scopeHeaderLine(companyId, branchUid)), format));
    }

    // -------------------------------------------------------------------------

    static String groupHeader(SalesSummaryGroupBy by) {
        return switch (by) {
            case CUSTOMER -> "Customer";
            case AGENT -> "Agent";
            case ROUTE -> "Route";
            case BRANCH -> "Branch";
            case DAY -> "Date";
            case CASHIER -> "Cashier";
        };
    }

    /** @param scopeLine the branches the summary really covers (RPT-05) */
    private TabularRenderModel flatten(SalesSummaryReportDto dto, String scopeLine) {
        List<String> headerLines = new ArrayList<>(ReportExportFormat.companyLines(dto.company()));
        headerLines.add("From " + dto.fromDate() + " To " + dto.toDate());
        headerLines.add("Grouped by: " + groupHeader(dto.groupBy()));
        headerLines.add(scopeLine);
        headerLines.add("Finalised invoices only. Returns and credit notes are not deducted. "
                + "Qty is in base units.");
        long foreignInvoices = dto.rows().stream()
                .mapToLong(SalesSummaryRowDto::foreignCurrencyInvoices).sum();
        if (foreignInvoices > 0) {
            headerLines.add("Amounts in " + text(dto.currency()) + ". " + foreignInvoices
                    + " invoice(s) in another currency are converted at the rate on each invoice.");
        }
        SalesSummaryTotalsDto t = dto.totals();
        if (t.groupsWithUnknownCost() > 0) {
            // Printed on the page itself: a foot that leaves cost out must say so, or the margin
            // total reads as complete and overstates profit.
            headerLines.add("Excluded from Cost and Margin totals: " + t.groupsWithUnknownCost()
                    + " group(s) with " + t.unknownCostItems()
                    + " item(s) sold before their stock had ever been costed");
        }

        // Cost columns exist only for a caller who may see cost — omitted, not printed blank.
        boolean cost = dto.costVisible();
        List<Column> columns = new ArrayList<>(List.of(
                new Column(groupHeader(dto.groupBy()), Align.LEFT),
                new Column("Invoices", Align.RIGHT),
                new Column("Qty", Align.RIGHT),
                new Column("Gross", Align.RIGHT),
                new Column("Discount", Align.RIGHT),
                new Column("VAT", Align.RIGHT),
                new Column("Net", Align.RIGHT)));
        if (cost) {
            columns.addAll(List.of(
                    new Column("Cost of Sales", Align.RIGHT),
                    new Column("Margin", Align.RIGHT),
                    new Column("Margin %", Align.RIGHT)));
        }

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (SalesSummaryRowDto r : dto.rows()) {
            String label = r.groupCode() != null && !r.groupCode().isBlank()
                    ? text(r.groupLabel()) + " (" + r.groupCode() + ")"
                    : text(r.groupLabel());
            List<String> row = new ArrayList<>(List.of(
                    label,
                    String.valueOf(r.invoiceCount()),
                    quantity(r.qty()),
                    amount(r.grossAmount()),
                    amount(r.discount()),
                    amount(r.vatAmount()),
                    amount(r.netAmount())));
            if (cost) {
                row.addAll(List.of(
                        amount(r.costOfSales()), amount(r.margin()), percent(r.marginPercent())));
            }
            rows.add(row);
        }

        List<String> totalsRow = new ArrayList<>(List.of(
                "TOTAL",
                String.valueOf(t.invoiceCount()),
                quantity(t.qty()),
                amount(t.grossAmount()),
                amount(t.discount()),
                amount(t.vatAmount()),
                amount(t.netAmount())));
        if (cost) {
            totalsRow.addAll(List.of(
                    amount(t.costOfSales()), amount(t.margin()), percent(t.marginPercent())));
        }

        return new TabularRenderModel("Sales Summary by " + groupHeader(dto.groupBy()),
                headerLines, dto.generatedAt(), columns, rows, totalsRow);
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
