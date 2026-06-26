package com.erp.api;

import com.erp.modules.bi.domain.dto.CrmSnapshotDto;
import com.erp.modules.bi.domain.dto.DashboardDto;
import com.erp.modules.bi.domain.dto.FinanceSummaryDto;
import com.erp.modules.bi.domain.dto.InventorySummaryDto;
import com.erp.modules.bi.domain.dto.SalesByBranchDto;
import com.erp.modules.bi.domain.dto.TrendDto;
import com.erp.modules.bi.domain.dto.WorkingCapitalDto;
import com.erp.modules.bi.service.BiExportFlattener;
import com.erp.modules.bi.service.DashboardService;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.ReportExporter;
import com.erp.platform.common.api.ApiResponse;
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
 * BI dashboard read + export endpoints (ADR-0037 D-1/D-5/D-9).
 *
 * <p>Permissions mirror V15/V81 seed:
 * <ul>
 *   <li>{@code BI.VIEW} — composite dashboard + per-panel reads.
 *   <li>{@code BI.FINANCE.VIEW} — finance/working-capital/trend panels.
 *   <li>{@code BI.OPS.VIEW} — inventory panel.
 *   <li>{@code BI.CRM.VIEW} — CRM panel.
 *   <li>{@code BI.EXPORT} — export endpoint.
 * </ul>
 *
 * <p>{@code companyId} is always an explicit {@code @RequestParam} — never inferred from the
 * principal (defense in depth; assertCanActIn fires inside the service). {@code ApiResponse.ok(...)}
 * style (DimensionReportController) for envelope consistency; export returns
 * {@code ResponseEntity<byte[]>} which {@code ApiResponseAdvice} passes through un-wrapped.
 */
@RestController
@RequestMapping("/api/v1/bi")
public class BiDashboardController {

    private final DashboardService    dashboardService;
    private final BiExportFlattener   flattener;
    private final ReportExporter      exporter;

    public BiDashboardController(DashboardService dashboardService,
                                  BiExportFlattener flattener,
                                  ReportExporter exporter) {
        this.dashboardService = dashboardService;
        this.flattener        = flattener;
        this.exporter         = exporter;
    }

    // -------------------------------------------------------------------------
    // Composite dashboard
    // -------------------------------------------------------------------------

    @GetMapping("/dashboard")
    @PreAuthorize("@perm.has('BI.VIEW')")
    public ApiResponse<DashboardDto> dashboard(
            @RequestParam Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId) {
        return ApiResponse.ok(dashboardService.dashboard(companyId, from, to, branchId));
    }

    // -------------------------------------------------------------------------
    // Per-panel endpoints (lazy-load / independent refresh)
    // -------------------------------------------------------------------------

    @GetMapping("/finance-summary")
    @PreAuthorize("@perm.has('BI.FINANCE.VIEW')")
    public ApiResponse<FinanceSummaryDto> financeSummary(
            @RequestParam Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();
        return ApiResponse.ok(dashboardService.financeSummary(companyId, effectiveFrom, effectiveTo));
    }

    @GetMapping("/working-capital")
    @PreAuthorize("@perm.has('BI.FINANCE.VIEW')")
    public ApiResponse<WorkingCapitalDto> workingCapital(@RequestParam Long companyId) {
        return ApiResponse.ok(dashboardService.workingCapital(companyId));
    }

    @GetMapping("/inventory")
    @PreAuthorize("@perm.has('BI.OPS.VIEW')")
    public ApiResponse<InventorySummaryDto> inventory(@RequestParam Long companyId) {
        return ApiResponse.ok(dashboardService.inventorySummary(companyId));
    }

    @GetMapping("/crm-summary")
    @PreAuthorize("@perm.has('BI.CRM.VIEW')")
    public ApiResponse<CrmSnapshotDto> crmSummary(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();
        return ApiResponse.ok(dashboardService.crmSnapshot(companyId, branchId, effectiveFrom, effectiveTo));
    }

    @GetMapping("/revenue-trend")
    @PreAuthorize("@perm.has('BI.FINANCE.VIEW')")
    public ApiResponse<TrendDto> revenueTrend(@RequestParam Long companyId) {
        return ApiResponse.ok(dashboardService.revenueTrend(companyId));
    }

    @GetMapping("/net-profit-trend")
    @PreAuthorize("@perm.has('BI.FINANCE.VIEW')")
    public ApiResponse<TrendDto> netProfitTrend(@RequestParam Long companyId) {
        return ApiResponse.ok(dashboardService.netProfitTrend(companyId));
    }

    @GetMapping("/sales-by-branch")
    @PreAuthorize("@perm.has('BI.FINANCE.VIEW')")
    public ApiResponse<SalesByBranchDto> salesByBranch(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to) {
        LocalDate effectiveFrom = from != null ? from : LocalDate.now().withDayOfMonth(1);
        LocalDate effectiveTo   = to   != null ? to   : LocalDate.now();
        return ApiResponse.ok(dashboardService.salesByBranch(companyId, branchId, effectiveFrom, effectiveTo));
    }

    // -------------------------------------------------------------------------
    // Export — flatten DashboardDto → StatementRenderModel → ReportExporter (D-9)
    // byte[] passes through ApiResponseAdvice un-wrapped (downloads keep own headers)
    // -------------------------------------------------------------------------

    @GetMapping("/dashboard/export")
    @PreAuthorize("@perm.has('BI.EXPORT')")
    public ResponseEntity<byte[]> export(
            @RequestParam Long companyId,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long branchId,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        DashboardDto dto = dashboardService.dashboard(companyId, from, to, branchId);
        ExportResult result = exporter.export(flattener.flatten(dto), format);
        return download(result);
    }

    // -------------------------------------------------------------------------

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
