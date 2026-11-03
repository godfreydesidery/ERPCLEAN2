package com.erp.api;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.sales.domain.dto.RestaurantBillRowDto;
import com.erp.modules.sales.domain.dto.RestaurantBillsSummaryDto;
import com.erp.modules.sales.domain.dto.RestaurantBillsReportDto;
import com.erp.modules.sales.domain.dto.RestaurantDashboardDto;
import com.erp.modules.sales.domain.dto.RestaurantDaypartDto;
import com.erp.modules.sales.domain.dto.RestaurantPaymentsReportDto;
import com.erp.modules.sales.domain.dto.RestaurantPendingBillRowDto;
import com.erp.modules.sales.domain.dto.RestaurantPendingBillsReportDto;
import com.erp.modules.sales.domain.dto.RestaurantPendingSummaryDto;
import com.erp.modules.sales.domain.dto.WaiterItemsReportDto;
import com.erp.modules.sales.domain.dto.WaiterSalesReportDto;
import com.erp.modules.sales.domain.dto.WaiterSalesRowDto;
import com.erp.modules.sales.service.RestaurantInsightsQuery;
import com.erp.modules.sales.service.RestaurantReportQuery;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * Restaurant reports per waiter (J COB 2026-10-30): sales, paid bills and pending bills, for one
 * waiter or all, each as a summary and in detail. Plus the restaurant dashboard (exported as the
 * Flash Report), sales by hour and day, and payments and order completion (2026-11-03). Gated like the Sales Report (owner ruling 2026-10-30: the existing report
 * permission): {@code SALES.INVOICE.VIEW} on screen, plus {@code REPORT.EXPORT} to download.
 */
@RestController
@RequestMapping("/api/v1/reports/restaurant")
public class RestaurantReportController {

    private final RestaurantReportQuery   query;
    private final RestaurantInsightsQuery insights;
    private final TabularExporter         exporter;
    private final BranchReadGuard         branchGuard;

    public RestaurantReportController(RestaurantReportQuery query,
                                      RestaurantInsightsQuery insights, TabularExporter exporter,
                                      BranchReadGuard branchGuard) {
        this.query       = query;
        this.insights    = insights;
        this.exporter    = exporter;
        this.branchGuard = branchGuard;
    }

    // ---------------------------------------------------------------- waiter sales

    @GetMapping("/waiter-sales")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public WaiterSalesReportDto waiterSales(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid) {
        return query.waiterSales(companyId(), fromDate, toDate, waiterUid, branchUid);
    }

    @GetMapping("/waiter-sales/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportWaiterSales(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = companyId();
        WaiterSalesReportDto dto =
                query.waiterSales(companyId, fromDate, toDate, waiterUid, branchUid);
        List<String> header = header(dto.company(), dto.fromDate(), dto.toDate(),
                branchGuard.scopeHeaderLine(companyId, branchUid), dto.waiterName(),
                dto.currency());
        List<List<String>> rows = new ArrayList<>();
        for (WaiterSalesRowDto r : dto.rows()) {
            rows.add(List.of(text(r.waiterName()), String.valueOf(r.bills()),
                    String.valueOf(r.guests()), amt(r.averageBill()), amt(r.amount())));
        }
        WaiterSalesRowDto t = dto.totals();
        return download(exporter.export(new TabularRenderModel("Sales by Waiter", header,
                dto.generatedAt(),
                List.of(new Column("Waiter", Align.LEFT), new Column("Bills", Align.RIGHT),
                        new Column("Guests", Align.RIGHT), new Column("Average bill", Align.RIGHT),
                        new Column("Amount", Align.RIGHT)),
                rows,
                List.of("TOTAL", String.valueOf(t.bills()), String.valueOf(t.guests()),
                        amt(t.averageBill()), amt(t.amount()))), format));
    }

    // ---------------------------------------------------------------- bills

    @GetMapping("/bills")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public RestaurantBillsReportDto bills(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid) {
        return query.bills(companyId(), fromDate, toDate, waiterUid, branchUid);
    }

    @GetMapping("/bills/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportBills(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = companyId();
        RestaurantBillsReportDto dto =
                query.bills(companyId, fromDate, toDate, waiterUid, branchUid);
        List<String> header = header(dto.company(), dto.fromDate(), dto.toDate(),
                branchGuard.scopeHeaderLine(companyId, branchUid), dto.waiterName(),
                dto.currency());
        List<List<String>> rows = new ArrayList<>();
        for (RestaurantBillRowDto r : dto.rows()) {
            boolean voided = !"FINALISED".equals(r.invoiceStatus());
            rows.add(List.of(text(r.paidAt()), text(r.orderNumber()), text(r.servedAt()),
                    text(r.waiterName()), text(r.cashierName()),
                    text(r.invoiceNumber()) + (voided ? " (VOID)" : ""),
                    text(r.tenders()), amt(r.amount())));
        }
        return download(exporter.export(new TabularRenderModel("Restaurant Bills", header,
                dto.generatedAt(),
                List.of(new Column("Paid", Align.LEFT), new Column("Order", Align.LEFT),
                        new Column("Table", Align.LEFT), new Column("Waiter", Align.LEFT),
                        new Column("Cashier", Align.LEFT), new Column("Receipt", Align.LEFT),
                        new Column("Paid by", Align.LEFT), new Column("Amount", Align.RIGHT)),
                rows,
                List.of("", "TOTAL", "", "", "", dto.billCount() + " bills", "",
                        amt(dto.totalAmount()))), format));
    }

    // ---------------------------------------------------------------- pending bills

    @GetMapping("/pending-bills")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public RestaurantPendingBillsReportDto pendingBills(
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid) {
        return query.pendingBills(companyId(), waiterUid, branchUid);
    }

    @GetMapping("/pending-bills/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportPendingBills(
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = companyId();
        RestaurantPendingBillsReportDto dto = query.pendingBills(companyId, waiterUid, branchUid);
        List<String> header = header(dto.company(), null, null,
                branchGuard.scopeHeaderLine(companyId, branchUid), dto.waiterName(),
                dto.currency());
        List<List<String>> rows = new ArrayList<>();
        for (RestaurantPendingBillRowDto r : dto.rows()) {
            rows.add(List.of(text(r.openedAt()), text(r.orderNumber()), text(r.servedAt()),
                    text(r.waiterName()),
                    "BILLED".equals(r.status()) ? "Bill printed" : "Open",
                    qty(r.items()), r.billedAmount() == null ? "" : amt(r.billedAmount())));
        }
        return download(exporter.export(new TabularRenderModel("Pending Bills", header,
                dto.generatedAt(),
                List.of(new Column("Opened", Align.LEFT), new Column("Order", Align.LEFT),
                        new Column("Table", Align.LEFT), new Column("Waiter", Align.LEFT),
                        new Column("Status", Align.LEFT), new Column("Items", Align.RIGHT),
                        new Column("Bill", Align.RIGHT)),
                rows,
                List.of("", "TOTAL", "", "", dto.openCount() + " open, " + dto.billedCount()
                        + " billed", "", amt(dto.billedAmount()))), format));
    }

    // ---------------------------------------------------------------- waiter items (detail)

    @GetMapping("/waiter-items")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public WaiterItemsReportDto waiterItems(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid) {
        return query.waiterItems(companyId(), fromDate, toDate, waiterUid, branchUid);
    }

    @GetMapping("/waiter-items/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportWaiterItems(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = companyId();
        WaiterItemsReportDto dto =
                query.waiterItems(companyId, fromDate, toDate, waiterUid, branchUid);
        List<String> header = header(dto.company(), dto.fromDate(), dto.toDate(),
                branchGuard.scopeHeaderLine(companyId, branchUid), dto.waiterName(),
                dto.currency());
        List<List<String>> rows = new ArrayList<>();
        for (WaiterItemsReportDto.Subtotal s : dto.waiters()) {
            for (WaiterItemsReportDto.Row r : dto.rows()) {
                if (r.waiterUid().equals(s.waiterUid())) {
                    rows.add(List.of(text(r.waiterName()), text(r.code()), text(r.name()),
                            text(r.unit()), qty(r.quantity()), amt(r.amount())));
                }
            }
            rows.add(List.of(s.waiterName() + " subtotal", "", s.bills() + " bills", "", "",
                    amt(s.amount())));
        }
        return download(exporter.export(new TabularRenderModel("Sales by Waiter - Items", header,
                dto.generatedAt(),
                List.of(new Column("Waiter", Align.LEFT), new Column("Code", Align.LEFT),
                        new Column("Item", Align.LEFT), new Column("Unit", Align.LEFT),
                        new Column("Qty", Align.RIGHT), new Column("Amount", Align.RIGHT)),
                rows,
                List.of("TOTAL", "", "", "", "", amt(dto.totalAmount()))), format));
    }

    // ---------------------------------------------------------------- bills (summary)

    @GetMapping("/bills-summary")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public RestaurantBillsSummaryDto billsSummary(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid) {
        return query.billsSummary(companyId(), fromDate, toDate, waiterUid, branchUid);
    }

    @GetMapping("/bills-summary/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportBillsSummary(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = companyId();
        RestaurantBillsSummaryDto dto =
                query.billsSummary(companyId, fromDate, toDate, waiterUid, branchUid);
        List<String> header = header(dto.company(), dto.fromDate(), dto.toDate(),
                branchGuard.scopeHeaderLine(companyId, branchUid), dto.waiterName(),
                dto.currency());
        List<Column> columns = new ArrayList<>(List.of(new Column("Waiter", Align.LEFT),
                new Column("Bills", Align.RIGHT), new Column("Guests", Align.RIGHT)));
        for (String t : dto.tenderTypes()) {
            columns.add(new Column(tenderLabel(t), Align.RIGHT));
        }
        columns.add(new Column("Amount", Align.RIGHT));
        List<List<String>> rows = new ArrayList<>();
        for (RestaurantBillsSummaryDto.Row r : dto.rows()) {
            rows.add(billsSummaryRow(r));
        }
        return download(exporter.export(new TabularRenderModel("Bills Paid - Summary", header,
                dto.generatedAt(), columns, rows, billsSummaryRow(dto.totals())), format));
    }

    private static List<String> billsSummaryRow(RestaurantBillsSummaryDto.Row r) {
        List<String> row = new ArrayList<>(List.of(text(r.waiterName()), String.valueOf(r.bills()),
                String.valueOf(r.guests())));
        for (BigDecimal t : r.tenders()) {
            row.add(amt(t));
        }
        row.add(amt(r.amount()));
        return row;
    }

    // ---------------------------------------------------------------- pending (summary)

    @GetMapping("/pending-summary")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public RestaurantPendingSummaryDto pendingSummary(
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid) {
        return query.pendingSummary(companyId(), waiterUid, branchUid);
    }

    @GetMapping("/pending-summary/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportPendingSummary(
            @RequestParam(required = false) String waiterUid,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = companyId();
        RestaurantPendingSummaryDto dto = query.pendingSummary(companyId, waiterUid, branchUid);
        List<String> header = header(dto.company(), null, null,
                branchGuard.scopeHeaderLine(companyId, branchUid), dto.waiterName(),
                dto.currency());
        List<List<String>> rows = new ArrayList<>();
        for (RestaurantPendingSummaryDto.Row r : dto.rows()) {
            rows.add(pendingSummaryRow(r));
        }
        return download(exporter.export(new TabularRenderModel("Pending Bills - Summary", header,
                dto.generatedAt(),
                List.of(new Column("Waiter", Align.LEFT), new Column("Open", Align.RIGHT),
                        new Column("Bill printed", Align.RIGHT), new Column("Items", Align.RIGHT),
                        new Column("Bill amount", Align.RIGHT),
                        new Column("Oldest order", Align.LEFT)),
                rows, pendingSummaryRow(dto.totals())), format));
    }

    private static List<String> pendingSummaryRow(RestaurantPendingSummaryDto.Row r) {
        return List.of(text(r.waiterName()), String.valueOf(r.open()), String.valueOf(r.billed()),
                qty(r.items()), amt(r.billedAmount()), text(r.oldestOpenedAt()));
    }

    // ---------------------------------------------------------------- dashboard / flash report

    @GetMapping("/dashboard")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public RestaurantDashboardDto dashboard(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid) {
        return insights.dashboard(companyId(), fromDate, toDate, branchUid);
    }

    /** The dashboard on paper: the Flash Report. */
    @GetMapping("/dashboard/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportDashboard(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = companyId();
        RestaurantDashboardDto d = insights.dashboard(companyId, fromDate, toDate, branchUid);
        List<String> header = header(d.company(), d.fromDate(), d.toDate(),
                branchGuard.scopeHeaderLine(companyId, branchUid), null, d.currency(), false);
        header.add("Compared with " + d.previousFromDate() + " to " + d.previousToDate());
        RestaurantDashboardDto.Kpis c = d.current();
        RestaurantDashboardDto.Kpis p = d.previous();
        List<List<String>> rows = new ArrayList<>();
        rows.add(kpi("Sales", amt(c.sales()), amt(p.sales()), c.sales(), p.sales()));
        rows.add(kpi("Receipts", count(c.receipts()), count(p.receipts()),
                BigDecimal.valueOf(c.receipts()), BigDecimal.valueOf(p.receipts())));
        rows.add(kpi("Average receipt", amt(c.averageReceipt()), amt(p.averageReceipt()),
                c.averageReceipt(), p.averageReceipt()));
        rows.add(kpi("Table orders paid", count(c.orders()), count(p.orders()),
                BigDecimal.valueOf(c.orders()), BigDecimal.valueOf(p.orders())));
        rows.add(kpi("Guests", count(c.guests()), count(p.guests()),
                BigDecimal.valueOf(c.guests()), BigDecimal.valueOf(p.guests())));
        rows.add(kpi("Spend per guest", amt(c.spendPerGuest()), amt(p.spendPerGuest()),
                c.spendPerGuest(), p.spendPerGuest()));
        rows.add(kpi("Discounts", amt(c.discounts()), amt(p.discounts()), c.discounts(),
                p.discounts()));
        rows.add(kpi("Complimentary", amt(c.complimentary()), amt(p.complimentary()),
                c.complimentary(), p.complimentary()));
        rows.add(kpi("Refunds", amt(c.refunds()), amt(p.refunds()), c.refunds(), p.refunds()));
        rows.add(kpi("Voided receipts", count(c.voidedReceipts()), count(p.voidedReceipts()),
                BigDecimal.valueOf(c.voidedReceipts()), BigDecimal.valueOf(p.voidedReceipts())));
        rows.add(kpi("Cancelled orders", count(c.cancelledOrders()), count(p.cancelledOrders()),
                BigDecimal.valueOf(c.cancelledOrders()),
                BigDecimal.valueOf(p.cancelledOrders())));
        rows.add(kpi("Voided items", qty(c.voidedItems()), qty(p.voidedItems()), c.voidedItems(),
                p.voidedItems()));

        RestaurantDashboardDto.Live live = d.live();
        section(rows, "RIGHT NOW");
        rows.add(List.of("Orders open", count(live.openOrders()), "", ""));
        rows.add(List.of("Bills printed, not paid", count(live.billedOrders()),
                amt(live.billedAmount()), ""));

        section(rows, "PAYMENTS");
        for (RestaurantDashboardDto.Tender t : d.tenders()) {
            rows.add(List.of(tenderLabel(t.tenderType()), amt(t.amount()),
                    count(t.payments()) + " payments", ""));
        }
        section(rows, "DEPARTMENTS");
        for (RestaurantDashboardDto.Department x : d.departments()) {
            rows.add(List.of(text(x.name()), amt(x.sales()), qty(x.quantity()) + " items", ""));
        }
        section(rows, "TOP ITEMS");
        for (RestaurantDashboardDto.Item x : d.topItems()) {
            rows.add(List.of(text(x.name()), amt(x.sales()),
                    qty(x.quantity()) + " " + text(x.unit()), ""));
        }
        section(rows, "WAITERS");
        for (WaiterSalesRowDto x : d.waiters()) {
            rows.add(List.of(text(x.waiterName()), amt(x.amount()), count(x.bills()) + " bills",
                    x.guests() > 0 ? count(x.guests()) + " guests" : ""));
        }
        if (!d.complimentary().isEmpty()) {
            section(rows, "COMPLIMENTARY");
            for (RestaurantDashboardDto.Comp x : d.complimentary()) {
                rows.add(List.of(text(x.receiptNumber()) + " " + text(x.at()), amt(x.amount()),
                        x.givenTo() != null ? "Given to " + x.givenTo() : "", ""));
            }
        }
        return download(exporter.export(new TabularRenderModel("Flash Report", header,
                d.generatedAt(),
                List.of(new Column("", Align.LEFT), new Column("This period", Align.RIGHT),
                        new Column("Previous period", Align.RIGHT),
                        new Column("Change", Align.RIGHT)),
                rows, null), format));
    }

    private static List<String> kpi(String label, String now, String before, BigDecimal a,
                                     BigDecimal b) {
        String change = "";
        if (a != null && b != null && b.signum() != 0) {
            BigDecimal pct = a.subtract(b).multiply(BigDecimal.valueOf(100))
                    .divide(b.abs(), 1, RoundingMode.HALF_UP);
            change = (pct.signum() > 0 ? "+" : "") + pct.toPlainString() + "%";
        }
        return List.of(label, now, before, change);
    }

    private static void section(List<List<String>> rows, String title) {
        rows.add(List.of("", "", "", ""));
        rows.add(List.of(title, "", "", ""));
    }

    // ---------------------------------------------------------------- by hour and weekday

    @GetMapping("/daypart")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public RestaurantDaypartDto daypart(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid) {
        return insights.daypart(companyId(), fromDate, toDate, branchUid);
    }

    @GetMapping("/daypart/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportDaypart(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = companyId();
        RestaurantDaypartDto d = insights.daypart(companyId, fromDate, toDate, branchUid);
        List<String> header = header(d.company(), d.fromDate(), d.toDate(),
                branchGuard.scopeHeaderLine(companyId, branchUid), null, d.currency(), false);
        List<Column> columns = new ArrayList<>(List.of(new Column("Time", Align.LEFT),
                new Column("Receipts", Align.RIGHT), new Column("Sales", Align.RIGHT),
                new Column("Average per day", Align.RIGHT)));
        for (String dept : d.departments()) {
            columns.add(new Column(dept, Align.RIGHT));
        }
        int width = columns.size();
        List<List<String>> rows = new ArrayList<>();
        rows.add(padded("BY HOUR OF THE DAY", width));
        for (RestaurantDaypartDto.Slot s : d.hours()) {
            if (s.receipts() > 0 || s.sales().signum() != 0) {
                rows.add(slotRow(s));
            }
        }
        rows.add(padded("", width));
        rows.add(padded("BY DAY OF THE WEEK", width));
        for (RestaurantDaypartDto.Slot s : d.weekdays()) {
            rows.add(slotRow(s));
        }
        return download(exporter.export(new TabularRenderModel("Sales by Hour and Day", header,
                d.generatedAt(), columns, rows, slotRow(d.totals())), format));
    }

    private static List<String> slotRow(RestaurantDaypartDto.Slot s) {
        List<String> row = new ArrayList<>(List.of(text(s.label()), count(s.receipts()),
                amt(s.sales()), amt(s.averagePerDay())));
        for (BigDecimal v : s.departmentSales()) {
            row.add(amt(v));
        }
        return row;
    }

    private static List<String> padded(String first, int width) {
        List<String> row = new ArrayList<>(width);
        row.add(first);
        while (row.size() < width) {
            row.add("");
        }
        return row;
    }

    // ---------------------------------------------------------------- payments and completion

    @GetMapping("/payments")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public RestaurantPaymentsReportDto payments(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid) {
        return insights.payments(companyId(), fromDate, toDate, branchUid);
    }

    @GetMapping("/payments/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportPayments(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = companyId();
        RestaurantPaymentsReportDto d = insights.payments(companyId, fromDate, toDate, branchUid);
        List<String> header = header(d.company(), d.fromDate(), d.toDate(),
                branchGuard.scopeHeaderLine(companyId, branchUid), null, d.currency(), false);
        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("PAYMENTS", "", "", "", "", "", "", ""));
        for (RestaurantPaymentsReportDto.Tender t : d.tenders()) {
            rows.add(List.of("", "", tenderLabel(t.tenderType()), count(t.payments()) + " payments",
                    "", t.share().toPlainString() + "%", "", amt(t.amount())));
        }
        rows.add(List.of("", "", "Total paid", "", "", "", "", amt(d.totalPaid())));

        RestaurantPaymentsReportDto.Completion c = d.completion();
        rows.add(List.of("ORDER COMPLETION", "", "", "", "", "", "", ""));
        rows.add(List.of("", "", "Orders opened", "", "", "", count(c.opened()), ""));
        rows.add(List.of("", "", "Paid", "", "", "", count(c.paid()), ""));
        rows.add(List.of("", "", "Cancelled", "", "", "", count(c.cancelled()), ""));
        rows.add(List.of("", "", "Not yet paid", "", "", "", count(c.pending()), ""));
        rows.add(List.of("", "", "Completion rate", "", "",
                c.completionRate() == null ? "" : c.completionRate().toPlainString() + "%", "",
                ""));

        rows.add(List.of("REFUNDS", "", "", "", "", "", "", ""));
        for (RestaurantPaymentsReportDto.Refund r : d.refunds()) {
            rows.add(List.of("", text(r.at()), text(r.receiptNumber()), "", text(r.by()),
                    text(r.reason()), "", amt(r.amount())));
        }
        rows.add(List.of("", "", "Total refunded", "", "", "", "", amt(d.refundTotal())));

        rows.add(List.of("VOIDED RECEIPTS", "", "", "", "", "", "", ""));
        for (RestaurantPaymentsReportDto.VoidedReceipt v : d.voidedReceipts()) {
            rows.add(List.of("", text(v.soldAt()), text(v.receiptNumber()), "", text(v.by()), "",
                    "", amt(v.amount())));
        }
        rows.add(List.of("", "", "Total voided", "", "", "", "", amt(d.voidedReceiptTotal())));

        rows.add(List.of("CANCELLED ORDERS", "", "", "", "", "", "", ""));
        for (RestaurantPaymentsReportDto.CancelledOrder o : d.cancelledOrders()) {
            rows.add(List.of("", text(o.cancelledAt()), text(o.orderNumber()), text(o.servedAt()),
                    text(o.waiterName()), text(o.reason()), qty(o.items()), ""));
        }

        rows.add(List.of("VOIDED ITEMS", "", "", "", "", "", "", ""));
        for (RestaurantPaymentsReportDto.VoidedItem v : d.voidedItems()) {
            rows.add(List.of("", text(v.voidedAt()), text(v.orderNumber()),
                    text(v.productName()) + (v.servedAt() != null ? " (" + v.servedAt() + ")" : ""),
                    text(v.waiterName()), text(v.reason()), qty(v.quantity()), ""));
        }
        return download(exporter.export(new TabularRenderModel("Payments and Order Completion",
                header, d.generatedAt(),
                List.of(new Column("Section", Align.LEFT), new Column("When", Align.LEFT),
                        new Column("Reference", Align.LEFT), new Column("Detail", Align.LEFT),
                        new Column("Waiter / by", Align.LEFT), new Column("Reason", Align.LEFT),
                        new Column("Qty", Align.RIGHT), new Column("Amount", Align.RIGHT)),
                rows, null), format));
    }

    // ---------------------------------------------------------------- shared

    private static Long companyId() {
        return RequestContext.get().companyId();
    }

    private static List<String> header(ReportCompanyHeaderDto c, String fromDate, String toDate,
                                       String scopeLine, String waiterName, String currency) {
        return header(c, fromDate, toDate, scopeLine, waiterName, currency, true);
    }

    /** {@code withWaiter} false for the reports that are not per waiter. */
    private static List<String> header(ReportCompanyHeaderDto c, String fromDate, String toDate,
                                       String scopeLine, String waiterName, String currency,
                                       boolean withWaiter) {
        List<String> lines = new ArrayList<>();
        if (c != null) {
            addIf(lines, c.name(), "");
            StringBuilder address = new StringBuilder();
            for (String part : new String[] {c.addressLine1(), c.addressLine2(), c.city(),
                    c.region(), c.country()}) {
                if (part != null && !part.isBlank()) {
                    address.append(address.length() > 0 ? ", " : "").append(part);
                }
            }
            addIf(lines, address.toString(), "");
            addIf(lines, c.contactPhone(), "Tel: ");
            addIf(lines, c.contactEmail(), "Email: ");
            addIf(lines, c.taxId(), "TIN: ");
            addIf(lines, c.vrn(), "VRN: ");
        }
        if (fromDate != null) {
            lines.add("From " + fromDate + " To " + toDate);
        }
        addIf(lines, scopeLine, "");
        if (withWaiter) {
            lines.add("Waiter: " + (waiterName != null ? waiterName : "All waiters"));
        }
        addIf(lines, currency, "Amounts in ");
        return lines;
    }

    private static void addIf(List<String> lines, String value, String prefix) {
        if (value != null && !value.isBlank()) {
            lines.add(prefix + value);
        }
    }

    private static String text(String s) {
        return s != null ? s : "";
    }

    private static String amt(BigDecimal v) {
        return v != null ? String.format("%,.2f", v) : "";
    }

    private static String count(long v) {
        return String.format("%,d", v);
    }

    /** CASH -> Cash, MOBILE_MONEY -> Mobile money. */
    private static String tenderLabel(String tender) {
        if (tender == null || tender.isBlank()) {
            return "";
        }
        String s = tender.replace('_', ' ').toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private static String qty(BigDecimal v) {
        return v != null ? v.stripTrailingZeros().toPlainString() : "";
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
