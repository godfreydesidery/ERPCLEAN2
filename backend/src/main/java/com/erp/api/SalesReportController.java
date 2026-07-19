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

    private final SalesReportQuery salesReportQuery;
    private final TabularExporter  exporter;

    public SalesReportController(SalesReportQuery salesReportQuery, TabularExporter exporter) {
        this.salesReportQuery = salesReportQuery;
        this.exporter         = exporter;
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
        return salesReportQuery.report(companyId, fromDate, toDate, agentUid, routeUid, supplierUid, branchUid);
    }

    @GetMapping("/export")
    @PreAuthorize("@perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportSalesReport(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String agentUid,
            @RequestParam(required = false) String routeUid,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(required = false) String branchUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        Long companyId = RequestContext.get().companyId();
        SalesReportDto dto = salesReportQuery.report(
                companyId, fromDate, toDate, agentUid, routeUid, supplierUid, branchUid);
        return download(exporter.export(flatten(dto), format));
    }

    // -------------------------------------------------------------------------

    private TabularRenderModel flatten(SalesReportDto dto) {
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
        if (dto.agentName() != null) {
            headerLines.add("Agent: " + dto.agentName());
        }
        if (dto.routeName() != null) {
            headerLines.add("Route: " + dto.routeName());
        }
        if (dto.supplierName() != null) {
            headerLines.add("Supplier: " + dto.supplierName());
        }

        List<Column> columns = List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Stock", Align.RIGHT),
                new Column("Qty", Align.RIGHT),
                new Column("Disc", Align.RIGHT),
                new Column("VAT", Align.RIGHT),
                new Column("Margin", Align.RIGHT),
                new Column("Amount", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (SalesReportRowDto r : dto.rows()) {
            rows.add(List.of(
                    nullToEmpty(r.productCode()),
                    nullToEmpty(r.productName()),
                    fmtQty(r.currentStock()),
                    fmtQty(r.qtySold()),
                    fmtAmt(r.discount()),
                    fmtAmt(r.vat()),
                    fmtAmt(r.margin()),
                    fmtAmt(r.amount())));
        }

        List<String> totalsRow = List.of(
                "", "TOTAL", "",
                fmtQty(dto.totals().qtySold()),
                fmtAmt(dto.totals().discount()),
                fmtAmt(dto.totals().vat()),
                fmtAmt(dto.totals().margin()),
                fmtAmt(dto.totals().amount()));

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
