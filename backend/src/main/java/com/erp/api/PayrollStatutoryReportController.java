package com.erp.api;

import com.erp.modules.hr.domain.dto.PayrollStatutoryPeriodReportDto;
import com.erp.modules.hr.domain.dto.StatutorySummaryDto;
import com.erp.modules.hr.domain.dto.StatutoryTotalsDto;
import com.erp.modules.hr.service.PayrollStatutoryReportQuery;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.CompanyLetterheadLines;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.reporting.service.ReportCompanyHeaderQuery;
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
 * Payroll Statutory report over a pay-date range (FR-HR-23): one row per APPROVED / POSTED / PAID
 * payroll run whose pay date falls in the range, with PAYE / NSSF / WCF / SDL / HESLB totals.
 *
 * <p>Gated {@code HR.PAYROLL.VIEW} — the same code as the payroll-run screens, whose {@code /lines}
 * already disclose every figure summed here. Company from the request context, never a parameter.
 */
@RestController
@RequestMapping("/api/v1/reports/payroll-statutory")
public class PayrollStatutoryReportController {

    private final PayrollStatutoryReportQuery query;
    private final TabularExporter             exporter;
    private final ReportCompanyHeaderQuery    companyHeaderQuery;

    public PayrollStatutoryReportController(PayrollStatutoryReportQuery query,
                                            TabularExporter exporter,
                                            ReportCompanyHeaderQuery companyHeaderQuery) {
        this.query              = query;
        this.exporter           = exporter;
        this.companyHeaderQuery = companyHeaderQuery;
    }

    @GetMapping
    @PreAuthorize("@perm.has('HR.PAYROLL.VIEW')")
    public PayrollStatutoryPeriodReportDto report(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate) {
        return query.forPeriod(RequestContext.get().companyId(), fromDate, toDate);
    }

    /** The on-screen gate AND {@code REPORT.EXPORT} — a download never reaches further than the screen. */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('HR.PAYROLL.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> export(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = RequestContext.get().companyId();
        PayrollStatutoryPeriodReportDto dto = query.forPeriod(companyId, fromDate, toDate);
        ExportResult result = exporter.export(flatten(dto, companyId), format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }

    // -------------------------------------------------------------------------

    private TabularRenderModel flatten(PayrollStatutoryPeriodReportDto dto, Long companyId) {
        List<String> headerLines = new ArrayList<>(
                CompanyLetterheadLines.of(companyHeaderQuery.forCompany(companyId)));
        headerLines.add("Pay dates from " + dto.fromDate() + " to " + dto.toDate()
                + " - Currency: " + dto.currency());
        headerLines.add("Counts approved, posted and paid payroll runs only.");

        List<Column> columns = List.of(
                new Column("Run", Align.LEFT),
                new Column("Period", Align.LEFT),
                new Column("Pay date", Align.LEFT),
                new Column("Status", Align.LEFT),
                new Column("Staff", Align.RIGHT),
                new Column("Gross", Align.RIGHT),
                new Column("PAYE", Align.RIGHT),
                new Column("NSSF Emp.", Align.RIGHT),
                new Column("NSSF Empr.", Align.RIGHT),
                new Column("WCF", Align.RIGHT),
                new Column("SDL", Align.RIGHT),
                new Column("HESLB", Align.RIGHT),
                new Column("Net", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.runs().size());
        for (StatutorySummaryDto r : dto.runs()) {
            rows.add(List.of(
                    r.runNumber(),
                    r.periodYear() + "-" + String.format("%02d", r.periodMonth()),
                    String.valueOf(r.payDate()),
                    r.status().name(),
                    String.valueOf(r.employeeCount()),
                    amt(r.grossTotal()), amt(r.payeTotal()), amt(r.nssfEmployeeTotal()),
                    amt(r.nssfEmployerTotal()), amt(r.wcfTotal()), amt(r.sdlTotal()),
                    amt(r.heslbTotal()), amt(r.netTotal())));
        }
        StatutoryTotalsDto t = dto.totals();
        List<String> totals = List.of(
                "TOTAL", t.runCount() + " runs", "", "", String.valueOf(t.payslipCount()),
                amt(t.grossTotal()), amt(t.payeTotal()), amt(t.nssfEmployeeTotal()),
                amt(t.nssfEmployerTotal()), amt(t.wcfTotal()), amt(t.sdlTotal()),
                amt(t.heslbTotal()), amt(t.netTotal()));

        List<String> footer = new ArrayList<>();
        footer.add("Employer cost (NSSF employer + WCF + SDL): " + amt(t.employerCostTotal()));
        if (dto.pendingRunCount() > 0) {
            footer.add(dto.pendingRunCount() + " payroll run(s) in this period are not approved yet "
                    + "and are not included.");
        }
        if (dto.reversedRunCount() > 0) {
            footer.add(dto.reversedRunCount() + " reversed payroll run(s) in this period are not "
                    + "included.");
        }

        return new TabularRenderModel("Payroll Statutory Report", headerLines, dto.generatedAt(),
                columns, rows, totals, footer);
    }

    private static String amt(BigDecimal v) {
        return v != null ? String.format("%,.2f", v) : "";
    }
}
