package com.erp.api;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.stock.domain.dto.OpeningValuationResultDto;
import com.erp.modules.stock.domain.dto.SetOpeningValuationRequest;
import com.erp.modules.stock.domain.dto.StockValuationReconDto;
import com.erp.modules.stock.domain.dto.StockValuationReportDto;
import com.erp.modules.stock.domain.dto.StockValuationRowDto;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockValuationQuery;
import com.erp.platform.security.RequestContext;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventory valuation REST surface (ADR-0020 D-6/D-5b).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/v1/stock/valuation/report — stock valuation report + GL recon bar
 *       (INVENTORY.VALUATION.VIEW).</li>
 *   <li>GET  /api/v1/stock/valuation/report/export — the same report as PDF / XLSX / CSV
 *       (INVENTORY.VALUATION.VIEW + REPORT.EXPORT).</li>
 *   <li>POST /api/v1/stock/valuation/opening — set opening valuation for an on-hand row
 *       (INVENTORY.OPENING.SET).</li>
 * </ul>
 *
 * <p>All responses are auto-wrapped in {@code ApiResponse<T>} by
 * {@link com.erp.platform.common.api.ApiResponseAdvice} — except the export, which returns raw
 * bytes.
 */
@RestController
@RequestMapping("/api/v1/stock/valuation")
public class StockValuationController {

    private final StockValuationQuery       valuationQuery;
    private final InventoryValuationService valuationService;
    private final TabularExporter           exporter;
    private final Clock                     clock;

    public StockValuationController(StockValuationQuery valuationQuery,
                                     InventoryValuationService valuationService,
                                     TabularExporter exporter,
                                     Clock clock) {
        this.valuationQuery   = valuationQuery;
        this.valuationService = valuationService;
        this.exporter         = exporter;
        this.clock            = clock;
    }

    /**
     * GET /api/v1/stock/valuation/report
     *
     * <p>Returns per-product on-hand quantities, avg costs, values and a GL reconciliation bar
     * (Σ on_hand_value vs GL 1300 balance). Branch-scoped to the caller's active branch context;
     * the report aggregates across branches within the company unless the query narrows further.
     *
     * <p><strong>{@code asOf} is no longer silently ignored.</strong> It used to be bound, parsed,
     * and then dropped — a user asking for 30 June got today's numbers with a 200 OK and no hint
     * that the date had not been applied. The query reads {@code stock_on_hand}, which is a
     * maintained CURRENT projection, so TODAY is the only date it can honestly answer; a past date
     * is rejected with a 400 that points at the period-windowed Stock Movement report instead.
     *
     * <p>A FUTURE date is rejected for the same reason and used not to be: it returned today's
     * numbers with a 200, so a report headed "as at 31 Dec 2027" was really "as at today" — the
     * identical silent-lie the past-date guard was added to stop. Nothing can be known about stock
     * that has not moved yet, so there is no honest answer to give.
     *
     * <p>A back-dated valuation cannot simply be replayed from {@code stock_movements} either:
     * opening-valuation and landed-cost paths adjust {@code on_hand_value} without writing a
     * correspondingly valued movement row, so Σ {@code value_amount} up to a date is not the
     * on-hand value at that date. Real point-in-time valuation needs a costed-history model — that
     * is a design change, not a parameter.
     */
    @GetMapping("/report")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW')")
    public StockValuationReportDto report(
            @RequestParam(name = "asOf", required = false) LocalDate asOf) {
        assertReportableDate(asOf);
        RequestContext.Principal principal = RequestContext.get();
        return valuationQuery.report(principal.companyId());
    }

    /**
     * GET /api/v1/stock/valuation/report/export — the same report as PDF / XLSX / CSV.
     *
     * <p>Mapped under {@code /report} rather than at a bare {@code /export} so the path names its
     * on-screen sibling: {@code ExportPermissionGateTest} pairs an export with its view handler by
     * path prefix, and a gate it cannot pair is a gate it cannot check.
     *
     * <p>Gated on the on-screen permission <em>as well as</em> {@code REPORT.EXPORT}: a download
     * discloses strictly more than one screen, so it must never be reachable by a caller the screen
     * itself refuses.
     *
     * <p>Runs the same {@code asOf} guard as {@link #report(LocalDate)} — a file that outlives the
     * request must not be able to claim a date the figures do not honour.
     */
    @GetMapping("/report/export")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportReport(
            @RequestParam(name = "asOf", required = false) LocalDate asOf,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        assertReportableDate(asOf);
        RequestContext.Principal principal = RequestContext.get();
        StockValuationReportDto dto = valuationQuery.report(principal.companyId());
        return download(exporter.export(flatten(dto), format));
    }

    /**
     * POST /api/v1/stock/valuation/opening
     *
     * <p>Sets the opening cost (avg_cost + on_hand_value) for an existing quantity-only on-hand row
     * and posts DR INVENTORY / CR OPENING_BALANCE_EQUITY. One-time per on-hand row — rejected if
     * already valued (avg_cost IS NOT NULL or on_hand_value != 0).
     *
     * <p>Posting date defaults to today when not supplied in the request body.
     */
    @PostMapping("/opening")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('INVENTORY.OPENING.SET')")
    public OpeningValuationResultDto setOpening(
            @Valid @RequestBody SetOpeningValuationRequest request) {
        return valuationService.setOpeningValue(request, LocalDate.now());
    }

    // -------------------------------------------------------------------------

    /** Shared by the screen and the export so a downloaded file can never honour a laxer rule. */
    private void assertReportableDate(LocalDate asOf) {
        LocalDate today = LocalDate.now(clock);
        if (asOf != null && asOf.isBefore(today)) {
            throw new IllegalArgumentException(
                    "Stock valuation is only available for the current position. "
                    + "To see stock as at an earlier date, use the Stock Movement report.");
        }
        if (asOf != null && asOf.isAfter(today)) {
            throw new IllegalArgumentException(
                    "Stock valuation is only available for the current position. "
                    + "A future date cannot be reported on — leave the date blank for today's "
                    + "stock position.");
        }
    }

    // -------------------------------------------------------------------------
    // Export flattening
    // -------------------------------------------------------------------------

    private TabularRenderModel flatten(StockValuationReportDto dto) {
        List<Column> columns = List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("On-Hand Qty", Align.RIGHT),
                new Column("Avg Cost", Align.RIGHT),
                new Column("Inventory Value", Align.RIGHT),
                new Column("Currency", Align.LEFT));

        List<List<String>> rows = new ArrayList<>(dto.rows().size());
        for (StockValuationRowDto r : dto.rows()) {
            rows.add(List.of(
                    nullToEmpty(r.productCode()),
                    nullToEmpty(r.productName()),
                    fmtQty(r.quantity()),
                    fmtAmt(r.avgCost()),
                    fmtAmt(r.value()),
                    nullToEmpty(r.currency())));
        }

        List<String> totalsRow = List.of(
                "", "TOTAL", "", "", fmtAmt(dto.totalValue()), nullToEmpty(dto.currency()));

        return new TabularRenderModel("Stock Valuation", headerLines(dto), dto.generatedAt(),
                columns, rows, totalsRow);
    }

    private List<String> headerLines(StockValuationReportDto dto) {
        List<String> lines = new ArrayList<>();
        ReportCompanyHeaderDto company = dto.company();
        if (company != null) {
            if (company.name() != null) {
                lines.add(company.name());
            }
            String address = joinAddress(company);
            if (address != null) {
                lines.add(address);
            }
            if (company.contactPhone() != null) {
                lines.add("Tel: " + company.contactPhone());
            }
            if (company.contactEmail() != null) {
                lines.add("Email: " + company.contactEmail());
            }
            if (company.taxId() != null) {
                lines.add("TIN: " + company.taxId());
            }
            if (company.vrn() != null) {
                lines.add("VRN: " + company.vrn());
            }
        }
        lines.add("Stock position as at today; quantities are in each product's base unit");

        // The whole point of this report is the tie-out to GL 1300, so the recon figures belong on
        // the page — a printout carrying only the rows is half the report, and the reader cannot
        // tell whether the total is trustworthy. ASCII only: the PDF renderer uses Helvetica with
        // the default encoding, which drops characters like a U+2212 minus sign.
        StockValuationReconDto recon = dto.recon();
        if (recon != null) {
            String currency = nullToEmpty(dto.currency());
            lines.add("Stock ledger total: " + fmtAmt(recon.computed()) + " " + currency);
            lines.add("GL 1300 Inventory balance: " + fmtAmt(recon.expected()) + " " + currency);
            lines.add("Difference: " + fmtAmt(recon.difference()) + " " + currency);
            lines.add(recon.ties()
                    ? "Reconciled to GL: yes"
                    : "Reconciled to GL: NO - the stock ledger and the GL are out of sync");
        }
        return lines;
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

    /**
     * Blank, never "0.00" — an uncosted row is unknown, not free. Mirrors the screen's "Not valued"
     * tag: a printed 0.00 would read as stock the shop got for nothing.
     */
    private String fmtAmt(BigDecimal amt) {
        return amt != null ? String.format("%,.2f", amt) : "";
    }

    /**
     * Whole quantities print without decimals; fractional ones keep 3 dp. {@code stock_on_hand
     * .quantity} is {@code NUMERIC(19,6)}, so weighed goods and pack conversions make fractions
     * ordinary data — rounding 0.4 kg to "0" would read as out of stock.
     */
    private String fmtQty(BigDecimal qty) {
        if (qty == null) {
            return "";
        }
        return qty.stripTrailingZeros().scale() <= 0
                ? String.format("%,.0f", qty)
                : String.format("%,.3f", qty);
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
