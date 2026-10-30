package com.erp.api;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.sales.domain.dto.RestaurantBillRowDto;
import com.erp.modules.sales.domain.dto.RestaurantBillsReportDto;
import com.erp.modules.sales.domain.dto.RestaurantPendingBillRowDto;
import com.erp.modules.sales.domain.dto.RestaurantPendingBillsReportDto;
import com.erp.modules.sales.domain.dto.WaiterSalesReportDto;
import com.erp.modules.sales.domain.dto.WaiterSalesRowDto;
import com.erp.modules.sales.service.RestaurantReportQuery;
import com.erp.platform.security.BranchReadGuard;
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
 * Restaurant reports per waiter (J COB 2026-10-30): sales, paid bills and pending bills, for one
 * waiter or all. Gated like the Sales Report (owner ruling 2026-10-30: the existing report
 * permission): {@code SALES.INVOICE.VIEW} on screen, plus {@code REPORT.EXPORT} to download.
 */
@RestController
@RequestMapping("/api/v1/reports/restaurant")
public class RestaurantReportController {

    private final RestaurantReportQuery query;
    private final TabularExporter       exporter;
    private final BranchReadGuard       branchGuard;

    public RestaurantReportController(RestaurantReportQuery query, TabularExporter exporter,
                                      BranchReadGuard branchGuard) {
        this.query       = query;
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

    // ---------------------------------------------------------------- shared

    private static Long companyId() {
        return RequestContext.get().companyId();
    }

    private static List<String> header(ReportCompanyHeaderDto c, String fromDate, String toDate,
                                       String scopeLine, String waiterName, String currency) {
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
        lines.add("Waiter: " + (waiterName != null ? waiterName : "All waiters"));
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
