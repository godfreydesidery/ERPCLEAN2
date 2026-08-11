package com.erp.api;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.stock.domain.dto.StockReportDto;
import com.erp.modules.stock.domain.dto.StockReportRowDto;
import com.erp.modules.stock.service.StockReportQuery;
import com.erp.platform.security.RequestContext;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Printable current-stock listing with selling price (SAM Electronix go-live).
 *
 * <p>Distinct from {@link StockValuationController} (qty × avg cost + GL reconciliation bar); this
 * is a plain on-hand + buying/selling-price register for the floor/warehouse, no GL tie-out.
 */
@RestController
@RequestMapping("/api/v1/stock/report")
public class StockReportController {

    private final StockReportQuery stockReportQuery;
    private final TabularExporter  exporter;

    public StockReportController(StockReportQuery stockReportQuery, TabularExporter exporter) {
        this.stockReportQuery = stockReportQuery;
        this.exporter         = exporter;
    }

    /**
     * @param branchUid optional; omit for the whole company, supply one to list a single branch.
     *
     * <p>Left optional rather than defaulting to the caller's branch: this register is used both at
     * head office (where company-wide is the point) and at a counter, and silently narrowing an
     * existing company-wide report to one branch would change what today's users see without asking.
     */
    @GetMapping
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW')")
    public StockReportDto stockReport(
            @RequestParam(required = false) String branchUid) {
        Long companyId = RequestContext.get().companyId();
        return stockReportQuery.report(companyId, branchUid);
    }

    /**
     * The export requires the on-screen gate ({@code INVENTORY.VALUATION.VIEW}) <em>as well as</em>
     * {@code REPORT.EXPORT}: a download discloses strictly more than one screen, so it must never be
     * reachable by a caller the screen itself refuses.
     */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportStockReport(
            @RequestParam(defaultValue = "PDF") ExportFormat format,
            @RequestParam(required = false) String branchUid) {
        Long companyId = RequestContext.get().companyId();
        StockReportDto dto = stockReportQuery.report(companyId, branchUid);
        return download(exporter.export(flatten(dto), format));
    }

    // -------------------------------------------------------------------------

    private TabularRenderModel flatten(StockReportDto dto) {
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

        List<Column> columns = List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Qty On Hand", Align.RIGHT),
                new Column("Buying Price", Align.RIGHT),
                new Column("Selling Price", Align.RIGHT),
                new Column("Value", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (StockReportRowDto r : dto.rows()) {
            rows.add(List.of(
                    nullToEmpty(r.productCode()),
                    nullToEmpty(r.productName()),
                    fmtQty(r.quantityOnHand()),
                    fmtAmt(r.buyingPrice()),
                    fmtAmt(r.sellingPrice()),
                    fmtAmt(r.value())));
        }

        List<String> totalsRow = List.of("", "TOTAL", "", "", "", fmtAmt(dto.totalValue()));

        return new TabularRenderModel("Stock Report", headerLines, dto.generatedAt(),
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
