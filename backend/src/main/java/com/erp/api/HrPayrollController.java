package com.erp.api;

import com.erp.modules.hr.domain.dto.CreatePayrollRunRequest;
import com.erp.modules.hr.domain.dto.DisburseRequest;
import com.erp.modules.hr.domain.dto.PayrollLineDto;
import com.erp.modules.hr.domain.dto.PayrollRunDto;
import com.erp.modules.hr.domain.dto.PayrollRunStatutoryReportDto;
import com.erp.modules.hr.domain.dto.PayslipDto;
import com.erp.modules.hr.domain.dto.StatutoryLineDto;
import com.erp.modules.hr.domain.dto.StatutorySummaryDto;
import com.erp.modules.hr.service.PayrollRunService;
import com.erp.modules.hr.service.PayrollStatutoryReportQuery;
import com.erp.modules.hr.service.PayslipService;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.CompanyLetterheadLines;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.reporting.service.ReportCompanyHeaderQuery;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/hr/payroll-runs")
public class HrPayrollController {

    private final PayrollRunService           service;
    private final PayslipService              payslipService;
    private final ScopeGuard                  scopeGuard;
    private final PayrollStatutoryReportQuery statutoryQuery;
    private final TabularExporter             exporter;
    private final ReportCompanyHeaderQuery    companyHeaderQuery;

    public HrPayrollController(PayrollRunService service, PayslipService payslipService,
                                ScopeGuard scopeGuard, PayrollStatutoryReportQuery statutoryQuery,
                                TabularExporter exporter, ReportCompanyHeaderQuery companyHeaderQuery) {
        this.service            = service;
        this.payslipService     = payslipService;
        this.scopeGuard         = scopeGuard;
        this.statutoryQuery     = statutoryQuery;
        this.exporter           = exporter;
        this.companyHeaderQuery = companyHeaderQuery;
    }

    @GetMapping
    @PreAuthorize("@perm.has('HR.PAYROLL.VIEW')")
    public ApiResponse<List<PayrollRunDto>> list(@RequestParam Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<PayrollRunDto> page = service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('HR.PAYROLL.RUN')")
    public PayrollRunDto create(@Valid @RequestBody CreatePayrollRunRequest req) {
        return service.create(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.VIEW')")
    public PayrollRunDto get(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @GetMapping("/uid/{uid}/lines")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.VIEW')")
    public List<PayrollLineDto> lines(@PathVariable String uid) {
        return service.listLines(uid);
    }

    /** Payslips generated for this run (posted onward), name-enriched, ordered by employee name. */
    @GetMapping("/uid/{uid}/payslips")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.VIEW')")
    public List<PayslipDto> payslips(@PathVariable String uid) {
        return payslipService.listByPayrollRunUid(uid);
    }

    @PostMapping("/uid/{uid}/calculate")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.RUN')")
    public PayrollRunDto calculate(@PathVariable String uid) {
        return service.calculate(uid);
    }

    @PostMapping("/uid/{uid}/approve")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.APPROVE')")
    public PayrollRunDto approve(@PathVariable String uid) {
        return service.approve(uid);
    }

    @PostMapping("/uid/{uid}/post")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.POST')")
    public PayrollRunDto post(@PathVariable String uid) {
        return service.post(uid);
    }

    @PostMapping("/uid/{uid}/disburse")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.DISBURSE')")
    public PayrollRunDto disburse(@PathVariable String uid,
                                   @Valid @RequestBody DisburseRequest req) {
        return service.disburse(uid, req);
    }

    @PostMapping("/uid/{uid}/reverse")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.REVERSE')")
    public PayrollRunDto reverse(@PathVariable String uid) {
        return service.reverse(uid);
    }

    /**
     * Statutory summary of the run (FR-HR-23): PAYE / NSSF (employee + employer) / WCF / SDL / HESLB
     * totals and the per-employee breakdown. Same gate as {@code /lines}, which already discloses
     * every per-employee amount shown here.
     */
    @GetMapping("/uid/{uid}/statutory-summary")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.VIEW')")
    public PayrollRunStatutoryReportDto statutorySummary(@PathVariable String uid) {
        return statutoryQuery.forRun(uid);
    }

    /** The statutory summary as a file: the on-screen gate AND {@code REPORT.EXPORT}. */
    @GetMapping("/uid/{uid}/statutory-summary/export")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportStatutorySummary(
            @PathVariable String uid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        PayrollRunStatutoryReportDto dto = statutoryQuery.forRun(uid);
        ExportResult result = exporter.export(flattenStatutory(dto), format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }

    /**
     * Export EFT batch as CSV for the payroll run (ADR-0040 D-11).
     * Gated by HR.PAYROLL.DISBURSE — same permission as disburse.
     */
    @GetMapping("/uid/{uid}/eft-export")
    @PreAuthorize("@perm.scoped(#uid,'payrollrun','HR.PAYROLL.DISBURSE')")
    public ResponseEntity<String> eftExport(@PathVariable String uid) {
        String csv = service.exportEftBatch(uid);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("text/csv"))
                .header("Content-Disposition",
                        "attachment; filename=\"eft-batch-" + uid + ".csv\"")
                .body(csv);
    }

    // -------------------------------------------------------------------------

    private TabularRenderModel flattenStatutory(PayrollRunStatutoryReportDto dto) {
        StatutorySummaryDto s = dto.summary();
        List<String> headerLines = new ArrayList<>(
                CompanyLetterheadLines.of(companyHeaderQuery.forCompany(dto.companyId())));
        headerLines.add("Payroll run " + s.runNumber() + " - period " + s.periodYear() + "-"
                + String.format("%02d", s.periodMonth()) + " - pay date " + s.payDate());
        headerLines.add("Status: " + s.status() + " - Currency: " + dto.currency());
        if (dto.provisional()) {
            headerLines.add("PROVISIONAL - this run is not approved, or was reversed, so these "
                    + "figures must not be filed.");
        }

        List<Column> columns = List.of(
                new Column("Emp No", Align.LEFT),
                new Column("Name", Align.LEFT),
                new Column("TIN", Align.LEFT),
                new Column("NSSF No", Align.LEFT),
                new Column("Gross", Align.RIGHT),
                new Column("PAYE", Align.RIGHT),
                new Column("NSSF Emp.", Align.RIGHT),
                new Column("NSSF Empr.", Align.RIGHT),
                new Column("WCF", Align.RIGHT),
                new Column("SDL", Align.RIGHT),
                new Column("HESLB", Align.RIGHT),
                new Column("Net", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.lines().size());
        for (StatutoryLineDto l : dto.lines()) {
            rows.add(List.of(
                    nz(l.employeeNumber()), nz(l.employeeName()), nz(l.tin()), nz(l.nssfNumber()),
                    amt(l.grossAmount()), amt(l.payeAmount()), amt(l.nssfEmployeeAmount()),
                    amt(l.nssfEmployerAmount()), amt(l.wcfAmount()), amt(l.sdlAmount()),
                    amt(l.heslbAmount()), amt(l.netAmount())));
        }
        List<String> totals = List.of(
                "", "TOTAL (" + s.employeeCount() + " employees)", "", "",
                amt(s.grossTotal()), amt(s.payeTotal()), amt(s.nssfEmployeeTotal()),
                amt(s.nssfEmployerTotal()), amt(s.wcfTotal()), amt(s.sdlTotal()),
                amt(s.heslbTotal()), amt(s.netTotal()));

        List<String> footer = List.of(
                "PAYE and SDL are paid to TRA; NSSF (employee + employer) to NSSF; WCF to WCF; "
                        + "HESLB to HESLB.",
                "Employer cost (NSSF employer + WCF + SDL): " + amt(s.employerCostTotal()));

        return new TabularRenderModel("Payroll Statutory Summary " + s.runNumber(), headerLines,
                dto.generatedAt(), columns, rows, totals, footer);
    }

    private static String nz(String s) {
        return s != null ? s : "";
    }

    private static String amt(BigDecimal v) {
        return v != null ? String.format("%,.2f", v) : "";
    }
}
