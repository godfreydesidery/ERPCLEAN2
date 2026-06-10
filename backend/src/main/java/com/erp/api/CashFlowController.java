package com.erp.api;

import com.erp.modules.reporting.domain.dto.CashFlowStatementDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.ReportExporter;
import com.erp.modules.reporting.export.StatementModelFlattener;
import com.erp.modules.reporting.service.ReportingService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import java.time.LocalDate;
import java.util.Map;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cash-Flow Statement (indirect) endpoints (ADR-0018 D-9/D-10, FR-REP-03/05).
 */
@RestController
@RequestMapping("/api/v1/reporting/cash-flow")
public class CashFlowController {

    private final ReportingService        reportingService;
    private final StatementModelFlattener flattener;
    private final ReportExporter          exporter;
    private final AuditService            auditService;

    public CashFlowController(ReportingService reportingService,
                               StatementModelFlattener flattener,
                               ReportExporter exporter,
                               AuditService auditService) {
        this.reportingService = reportingService;
        this.flattener        = flattener;
        this.exporter         = exporter;
        this.auditService     = auditService;
    }

    @GetMapping
    @PreAuthorize("@perm.has('REPORT.CASHFLOW.VIEW') or @perm.has('REPORT.VIEW')")
    public CashFlowStatementDto get(
            @RequestParam Long companyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareTo) {
        return reportingService.cashFlow(companyId, fromDate, toDate, compareFrom, compareTo);
    }

    @GetMapping("/export")
    @PreAuthorize("(@perm.has('REPORT.CASHFLOW.VIEW') or @perm.has('REPORT.VIEW')) and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> export(
            @RequestParam Long companyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate compareTo,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {

        CashFlowStatementDto dto = reportingService.cashFlow(companyId, fromDate, toDate, compareFrom, compareTo);

        auditService.record(AuditEvent.of(AuditActions.REPORT_EXPORT, "cash_flow", companyId, null)
                .detail(Map.of("period", fromDate + "/" + toDate, "format", format.name())));

        ExportResult result = exporter.export(flattener.flatten(dto), format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
