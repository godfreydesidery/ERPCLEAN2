package com.erp.api;

import static com.erp.modules.reporting.export.ReportExportFormat.amount;
import static com.erp.modules.reporting.export.ReportExportFormat.quantity;
import static com.erp.modules.reporting.export.ReportExportFormat.text;

import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.ReportExportFormat;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.stock.domain.dto.ReorderReportDto;
import com.erp.modules.stock.domain.dto.ReorderRowDto;
import com.erp.modules.stock.service.ReorderReportQuery;
import com.erp.platform.security.PermissionChecks;
import com.erp.platform.security.RequestContext;
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
 * Reorder Report — stock lines at or below their reorder level, with the preferred supplier.
 *
 * <p>Gated {@code STOCK.VIEW}: the storekeeper who reorders is the reader. Buying prices are
 * {@code INVENTORY.VALUATION.VIEW}, so — as on the Item Inquiry — the last-cost and order-value
 * columns are read only for a caller who also holds that code, and are left OUT of the screen and
 * the export otherwise (never printed blank, which would read as "no cost on record").
 */
@RestController
@RequestMapping("/api/v1/reports/reorder")
public class ReorderReportController {

    private final ReorderReportQuery query;
    private final TabularExporter    exporter;
    private final PermissionChecks   perm;

    public ReorderReportController(ReorderReportQuery query, TabularExporter exporter,
                                   PermissionChecks perm) {
        this.query    = query;
        this.exporter = exporter;
        this.perm     = perm;
    }

    @GetMapping
    @PreAuthorize("@perm.has('STOCK.VIEW')")
    public ReorderReportDto reorder(@RequestParam(required = false) String branchUid,
                                    @RequestParam(required = false) String supplierUid) {
        return query.report(RequestContext.get().companyId(), branchUid, supplierUid,
                perm.has("INVENTORY.VALUATION.VIEW"));
    }

    /** On-screen gate AND {@code REPORT.EXPORT}; the cost columns follow the same entitlement. */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('STOCK.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportReorder(
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String supplierUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        ReorderReportDto dto = query.report(RequestContext.get().companyId(), branchUid,
                supplierUid, perm.has("INVENTORY.VALUATION.VIEW"));
        return download(exporter.export(flatten(dto), format));
    }

    // -------------------------------------------------------------------------

    private TabularRenderModel flatten(ReorderReportDto dto) {
        boolean cost = dto.costVisible();
        List<String> headerLines = new ArrayList<>(ReportExportFormat.companyLines(dto.company()));
        headerLines.add("Branch: " + (dto.branchName() != null
                ? dto.branchName() : "All branches (whole company)"));
        if (dto.supplierName() != null) {
            headerLines.add("Preferred supplier: " + dto.supplierName());
        }
        headerLines.add("Items at or below their reorder level: " + dto.itemCount());
        if (cost && dto.rowsWithoutCost() > 0) {
            headerLines.add("Not in the order value: " + dto.rowsWithoutCost()
                    + " item(s) never received, so there is no last cost");
        }

        List<Column> columns = new ArrayList<>(List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Branch / Location", Align.LEFT),
                new Column("On Hand", Align.RIGHT),
                new Column("Reorder Level", Align.RIGHT),
                new Column("Shortfall", Align.RIGHT),
                new Column("Suggested Qty", Align.RIGHT),
                new Column("Preferred Supplier", Align.LEFT)));
        if (cost) {
            columns.add(new Column("Last Cost", Align.RIGHT));
            columns.add(new Column("Order Value", Align.RIGHT));
        }

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (ReorderRowDto r : dto.rows()) {
            List<String> line = new ArrayList<>(List.of(
                    text(r.productCode()),
                    text(r.productName()),
                    text(r.branchName()) + " / " + text(r.locationName()),
                    quantity(r.onHand()),
                    quantity(r.reorderLevel()),
                    quantity(r.shortfall()),
                    quantity(r.suggestedOrderQty()),
                    text(r.supplierName())));
            if (cost) {
                line.add(amount(r.lastCost()));
                line.add(amount(r.estimatedOrderValue()));
            }
            rows.add(line);
        }

        List<String> totalsRow = null;
        if (cost) {
            totalsRow = new ArrayList<>(List.of("", "TOTAL", "", "", "", "", "", "", ""));
            totalsRow.add(amount(dto.estimatedOrderValue()));
        }

        return new TabularRenderModel("Reorder Report", headerLines, dto.generatedAt(),
                columns, rows, totalsRow);
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
