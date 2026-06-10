package com.erp.api;

import com.erp.modules.reporting.domain.dto.AccountLedgerDto;
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
 * GL account-ledger drill-down endpoints (ADR-0018 D-9/D-10, FR-REP-04/05).
 * accountUid = the uid of a chart_of_accounts row belonging to companyId.
 */
@RestController
@RequestMapping("/api/v1/reporting/account-ledger")
public class AccountLedgerController {

    private final ReportingService        reportingService;
    private final StatementModelFlattener flattener;
    private final ReportExporter          exporter;
    private final AuditService            auditService;

    public AccountLedgerController(ReportingService reportingService,
                                    StatementModelFlattener flattener,
                                    ReportExporter exporter,
                                    AuditService auditService) {
        this.reportingService = reportingService;
        this.flattener        = flattener;
        this.exporter         = exporter;
        this.auditService     = auditService;
    }

    @GetMapping
    @PreAuthorize("@perm.has('REPORT.LEDGER.VIEW') or @perm.has('REPORT.VIEW')")
    public AccountLedgerDto get(
            @RequestParam Long companyId,
            @RequestParam String accountUid,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reportingService.accountLedger(companyId, accountUid, fromDate, toDate, page, size);
    }

    @GetMapping("/export")
    @PreAuthorize("(@perm.has('REPORT.LEDGER.VIEW') or @perm.has('REPORT.VIEW')) and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> export(
            @RequestParam Long companyId,
            @RequestParam String accountUid,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "1000") int size,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {

        AccountLedgerDto dto = reportingService.accountLedger(
                companyId, accountUid, fromDate, toDate, page, size);

        auditService.record(AuditEvent.of(AuditActions.REPORT_EXPORT, "account_ledger", companyId, null)
                .detail(Map.of("accountUid", accountUid, "format", format.name())));

        ExportResult result = exporter.export(flattener.flatten(dto), format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
