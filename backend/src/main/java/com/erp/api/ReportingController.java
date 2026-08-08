package com.erp.api;

import com.erp.modules.reporting.domain.dto.AccountLedgerDto;
import com.erp.modules.reporting.domain.dto.BalanceSheetDto;
import com.erp.modules.reporting.domain.dto.CashFlowStatementDto;
import com.erp.modules.reporting.domain.dto.IncomeStatementDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.ReportExporter;
import com.erp.modules.reporting.export.StatementModelFlattener;
import com.erp.modules.reporting.service.ReportingService;
import java.time.LocalDate;
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
 * Financial statement read + export endpoints (ADR-0018, FR-REP-01..08).
 * Read-only over the GL — computed on demand, nothing stored, nothing posted.
 * Each statement carries its own reconciliation self-check (BS balances, CF ties to cash,
 * P&amp;L net == INCOME−EXPENSE movement). assertCanActIn(companyId) runs inside the service.
 *
 * <p>Distinct from {@code CashFlowController} (Cash &amp; Bank). Permissions: REPORT.* (V15).
 */
@RestController
@RequestMapping("/api/v1/reports")
public class ReportingController {

    private final ReportingService        reporting;
    private final StatementModelFlattener flattener;
    private final ReportExporter          exporter;

    public ReportingController(ReportingService reporting,
                               StatementModelFlattener flattener,
                               ReportExporter exporter) {
        this.reporting = reporting;
        this.flattener = flattener;
        this.exporter  = exporter;
    }

    // -------------------------------------------------------------------------
    // Income Statement / P&L
    // -------------------------------------------------------------------------

    @GetMapping("/income-statement")
    @PreAuthorize("@perm.has('REPORT.PL.VIEW')")
    public IncomeStatementDto incomeStatement(
            @RequestParam Long companyId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate cmpFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate cmpTo) {
        return reporting.incomeStatement(companyId, fromDate, toDate, cmpFrom, cmpTo);
    }

    // Every export below requires its statement's own VIEW gate AS WELL AS REPORT.EXPORT. A download
    // discloses strictly more than one on-screen page, so it must never be reachable by a caller the
    // screen itself refuses; gating a download on REPORT.EXPORT alone inverts that.
    @GetMapping("/income-statement/export")
    @PreAuthorize("@perm.has('REPORT.PL.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> incomeStatementExport(
            @RequestParam Long companyId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate cmpFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate cmpTo,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        IncomeStatementDto dto = reporting.incomeStatement(companyId, fromDate, toDate, cmpFrom, cmpTo);
        return download(exporter.export(flattener.flatten(dto), format));
    }

    // -------------------------------------------------------------------------
    // Balance Sheet
    // -------------------------------------------------------------------------

    @GetMapping("/balance-sheet")
    @PreAuthorize("@perm.has('REPORT.BS.VIEW')")
    public BalanceSheetDto balanceSheet(
            @RequestParam Long companyId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate asAtDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate compareAsAt) {
        return reporting.balanceSheet(companyId, asAtDate, compareAsAt);
    }

    @GetMapping("/balance-sheet/export")
    @PreAuthorize("@perm.has('REPORT.BS.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> balanceSheetExport(
            @RequestParam Long companyId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate asAtDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate compareAsAt,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        BalanceSheetDto dto = reporting.balanceSheet(companyId, asAtDate, compareAsAt);
        return download(exporter.export(flattener.flatten(dto), format));
    }

    // -------------------------------------------------------------------------
    // Cash-Flow Statement (indirect)
    // -------------------------------------------------------------------------

    @GetMapping("/cash-flow")
    @PreAuthorize("@perm.has('REPORT.CASHFLOW.VIEW')")
    public CashFlowStatementDto cashFlow(
            @RequestParam Long companyId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate cmpFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate cmpTo) {
        return reporting.cashFlow(companyId, fromDate, toDate, cmpFrom, cmpTo);
    }

    @GetMapping("/cash-flow/export")
    @PreAuthorize("@perm.has('REPORT.CASHFLOW.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> cashFlowExport(
            @RequestParam Long companyId,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate cmpFrom,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate cmpTo,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        CashFlowStatementDto dto = reporting.cashFlow(companyId, fromDate, toDate, cmpFrom, cmpTo);
        return download(exporter.export(flattener.flatten(dto), format));
    }

    // -------------------------------------------------------------------------
    // GL Account Ledger drill-down
    // -------------------------------------------------------------------------

    @GetMapping("/account-ledger")
    @PreAuthorize("@perm.has('REPORT.LEDGER.VIEW')")
    public AccountLedgerDto accountLedger(
            @RequestParam Long companyId,
            @RequestParam String accountUid,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return reporting.accountLedger(companyId, accountUid, fromDate, toDate, page, size);
    }

    @GetMapping("/account-ledger/export")
    @PreAuthorize("@perm.has('REPORT.LEDGER.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> accountLedgerExport(
            @RequestParam Long companyId,
            @RequestParam String accountUid,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        // Export a bounded ledger page — NOT Integer.MAX_VALUE, which would materialise a multi-year
        // ledger (potentially millions of lines) into memory and OOM the app (NFR-REP-02). A single
        // export is capped at LEDGER_EXPORT_MAX_ROWS; narrow the date range for very long ledgers.
        AccountLedgerDto dto = reporting.accountLedger(companyId, accountUid, fromDate, toDate, 0, LEDGER_EXPORT_MAX_ROWS);
        return download(exporter.export(flattener.flatten(dto), format));
    }

    /** Bound on a single ledger export to keep memory/response size sane (NFR-REP-02). */
    private static final int LEDGER_EXPORT_MAX_ROWS = 10_000;

    // -------------------------------------------------------------------------

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
