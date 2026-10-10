package com.erp.api;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.sales.domain.dto.SalesReportDto;
import com.erp.modules.sales.domain.dto.SalesReportRowDto;
import com.erp.modules.sales.service.SalesReportQuery;
import com.erp.platform.security.BranchReadGuard;
import com.erp.platform.security.PermissionChecks;
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
 * Per-product Sales Register over a date range (SAM Electronix go-live).
 *
 * <p>{@code amount} is GROSS (VAT-inclusive) sales; {@code margin} = net sales − cost-of-sale at
 * time of sale. All heavy lifting (period window, tenant scope, optional filter resolution, COGS
 * match) lives in {@link SalesReportQuery} — this controller only flattens the DTO for export.
 */
@RestController
@RequestMapping("/api/v1/reports/sales")
public class SalesReportController {

    /** Cost and margin are cost data (owner ruling 2026-10-10, ADM-14). */
    static final String COST_PERMISSION = "INVENTORY.VALUATION.VIEW";

    private final SalesReportQuery salesReportQuery;
    private final TabularExporter  exporter;
    private final PermissionChecks perm;
    private final BranchReadGuard  branchGuard;

    public SalesReportController(SalesReportQuery salesReportQuery, TabularExporter exporter,
                                 PermissionChecks perm, BranchReadGuard branchGuard) {
        this.salesReportQuery = salesReportQuery;
        this.exporter         = exporter;
        this.perm             = perm;
        this.branchGuard      = branchGuard;
    }

    /**
     * The report as this caller may see it: counter staff (SALES.INVOICE.VIEW without
     * INVENTORY.VALUATION.VIEW) keep the report but get every margin withheld, flagged by
     * {@code costVisible=false}. Root passes {@code perm.has} as usual.
     */
    private SalesReportDto visibleTo(SalesReportDto dto) {
        return perm.has(COST_PERMISSION) ? dto : dto.withoutCost();
    }

    @GetMapping
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW')")
    public SalesReportDto salesReport(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String agentUid,
            @RequestParam(required = false) String routeUid,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(required = false) String branchUid) {
        Long companyId = RequestContext.get().companyId();
        return visibleTo(salesReportQuery.report(
                companyId, fromDate, toDate, agentUid, routeUid, supplierUid, branchUid));
    }

    /**
     * The export requires the on-screen gate ({@code SALES.INVOICE.VIEW}) <em>as well as</em>
     * {@code REPORT.EXPORT}: a download discloses strictly more than one screen, so it must never be
     * reachable by a caller the screen itself refuses.
     */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('SALES.INVOICE.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportSalesReport(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String agentUid,
            @RequestParam(required = false) String routeUid,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = RequestContext.get().companyId();
        SalesReportDto dto = visibleTo(salesReportQuery.report(
                companyId, fromDate, toDate, agentUid, routeUid, supplierUid, branchUid));
        return download(exporter.export(
                flatten(dto, branchGuard.scopeHeaderLine(companyId, branchUid)), format));
    }

    // -------------------------------------------------------------------------

    /**
     * @param scopeLine the branches the report really covers (RPT-05) — "Branch: All branches",
     *                  one branch, or a branch-limited caller's own branches
     */
    TabularRenderModel flatten(SalesReportDto dto, String scopeLine) {
        List<String> headerLines = new ArrayList<>();
        ReportCompanyHeaderDto company = dto.company();
        if (company != null) {
            if (company.name() != null) {
                headerLines.add(company.name());
            }
            String address = joinAddress(company);
            if (address != null) {
                headerLines.add(address);
            }
            if (company.contactPhone() != null) {
                headerLines.add("Tel: " + company.contactPhone());
            }
            if (company.contactEmail() != null) {
                headerLines.add("Email: " + company.contactEmail());
            }
            if (company.taxId() != null) {
                headerLines.add("TIN: " + company.taxId());
            }
            if (company.vrn() != null) {
                headerLines.add("VRN: " + company.vrn());
            }
        }
        headerLines.add("From " + dto.fromDate() + " To " + dto.toDate());
        if (scopeLine != null) {
            headerLines.add(scopeLine);
        }
        if (dto.agentName() != null) {
            headerLines.add("Agent: " + dto.agentName());
        }
        if (dto.routeName() != null) {
            headerLines.add("Route: " + dto.routeName());
        }
        if (dto.supplierName() != null) {
            headerLines.add("Supplier: " + dto.supplierName());
        }

        // Margin is a column only for a caller who may see cost; for anyone else it is omitted,
        // not printed blank (a blank column reads as "no margin", which is a different claim).
        boolean cost = dto.costVisible();
        List<Column> columns = new ArrayList<>(List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Stock", Align.RIGHT),
                new Column("Qty", Align.RIGHT),
                new Column("Disc", Align.RIGHT),
                new Column("VAT", Align.RIGHT)));
        if (cost) {
            columns.add(new Column("Margin", Align.RIGHT));
        }
        columns.add(new Column("Amount", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (SalesReportRowDto r : dto.rows()) {
            List<String> row = new ArrayList<>(List.of(
                    nullToEmpty(r.productCode()),
                    nullToEmpty(r.productName()),
                    fmtQty(r.currentStock()),
                    fmtQty(r.qtySold()),
                    fmtAmt(r.discount()),
                    fmtAmt(r.vat())));
            if (cost) {
                row.add(fmtAmt(r.margin()));
            }
            row.add(fmtAmt(r.amount()));
            rows.add(row);
        }

        List<String> totalsRow = new ArrayList<>(List.of(
                "", "TOTAL", "",
                fmtQty(dto.totals().qtySold()),
                fmtAmt(dto.totals().discount()),
                fmtAmt(dto.totals().vat())));
        if (cost) {
            totalsRow.add(fmtAmt(dto.totals().margin()));
        }
        totalsRow.add(fmtAmt(dto.totals().amount()));

        return new TabularRenderModel("Sales Report", headerLines, dto.generatedAt(),
                columns, rows, totalsRow);
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

    private String fmtAmt(BigDecimal amt) {
        return amt != null ? String.format("%,.2f", amt) : "";
    }

    private String fmtQty(BigDecimal qty) {
        return qty != null ? String.format("%,.0f", qty) : "";
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
