package com.erp.api;

import com.erp.modules.gl.domain.dto.TrialBalanceDto;
import com.erp.modules.gl.domain.dto.TrialBalanceRowDto;
import com.erp.modules.gl.service.TrialBalanceQuery;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Trial balance read + export endpoints (ADR-0013, FR-GL-16).
 * Computed on demand; not stored. Nets to zero on a correct set of books.
 * Permission: GL.VIEW to read; GL.VIEW + REPORT.EXPORT to download.
 *
 * <p>The export mirrors its siblings in {@code ReportingController} (P&amp;L, balance sheet, cash
 * flow) and {@code ProductStockReportController}: {@code /export} directly beside the on-screen
 * handler, gated on the screen's own permission PLUS {@code REPORT.EXPORT}. A download discloses
 * strictly more than one on-screen page, so it must never be reachable by a caller the screen
 * refuses — the inversion {@code ExportPermissionGateTest} exists to prevent.
 */
@RestController
@RequestMapping("/api/v1/gl/trial-balance")
public class TrialBalanceController {

    private final TrialBalanceQuery query;
    private final TabularExporter   exporter;

    public TrialBalanceController(TrialBalanceQuery query, TabularExporter exporter) {
        this.query    = query;
        this.exporter = exporter;
    }

    /** Full trial balance for the company (all periods). */
    @GetMapping
    @PreAuthorize("@perm.has('GL.VIEW')")
    public TrialBalanceDto get(@RequestParam Long companyId) {
        return query.compute(companyId);
    }

    /** Trial balance filtered to a single fiscal period. */
    @GetMapping("/period")
    @PreAuthorize("@perm.has('GL.VIEW')")
    public TrialBalanceDto getForPeriod(@RequestParam Long companyId,
                                        @RequestParam Long periodId) {
        return query.computeForPeriod(companyId, periodId);
    }

    /**
     * The same trial balance as a file — PDF (default), Excel or CSV.
     *
     * <p>{@code periodId} is optional and mirrors the two reads above: omit it for the whole-company,
     * all-periods figures; give it to print one fiscal period. A period-close pack starts with this
     * page, which is why it must be printable at all.
     */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('GL.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> export(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long periodId,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        TrialBalanceDto dto = periodId != null
                ? query.computeForPeriod(companyId, periodId)
                : query.compute(companyId);
        return download(exporter.export(flatten(dto), format));
    }

    // -------------------------------------------------------------------------

    /** Column indexes are asserted by {@code TrialBalanceExportTest} — keep the two in step. */
    private TabularRenderModel flatten(TrialBalanceDto dto) {
        List<Column> columns = List.of(
                new Column("Code", Align.LEFT),
                new Column("Account Name", Align.LEFT),
                new Column("Type", Align.LEFT),
                new Column("Debit", Align.RIGHT),
                new Column("Credit", Align.RIGHT),
                new Column("Net", Align.RIGHT));

        List<TrialBalanceRowDto> source = dto.rows() != null ? dto.rows() : List.of();
        List<List<String>> rows = new ArrayList<>(source.size());
        for (TrialBalanceRowDto r : source) {
            rows.add(List.of(
                    nullToEmpty(r.accountCode()),
                    nullToEmpty(r.accountName()),
                    r.accountType() != null ? r.accountType().name() : "",
                    fmtSide(r.totalDebit()),
                    fmtSide(r.totalCredit()),
                    fmtAmt(r.net())));
        }

        // A trial balance that does not show its two totals is not a trial balance.
        List<String> totalsRow = List.of(
                "", "TOTAL", "",
                fmtAmt(dto.totalDebits()),
                fmtAmt(dto.totalCredits()),
                fmtAmt(difference(dto)));

        return new TabularRenderModel("Trial Balance", headerLines(dto),
                dto.generatedAt() != null ? dto.generatedAt() : "",
                columns, rows, totalsRow);
    }

    /**
     * The letterhead. ASCII ONLY — the PDF renderer draws Helvetica with its default (Cp1252)
     * encoding, which silently DROPS characters outside it (U+2212 MINUS SIGN being the one that bit
     * us): an out-of-balance figure would print without its sign, which is the worst possible way to
     * lose a character on a trial balance.
     */
    private List<String> headerLines(TrialBalanceDto dto) {
        List<String> lines = new ArrayList<>();
        ReportCompanyHeaderDto c = dto.company();
        if (c != null) {
            addIfPresent(lines, c.name());
            String address = joinAddress(c);
            if (address != null) {
                lines.add(address);
            }
            if (c.contactPhone() != null) {
                lines.add("Tel: " + c.contactPhone());
            }
            if (c.contactEmail() != null) {
                lines.add("Email: " + c.contactEmail());
            }
            if (c.taxId() != null) {
                lines.add("TIN: " + c.taxId());
            }
            if (c.vrn() != null) {
                lines.add("VRN: " + c.vrn());
            }
        }
        lines.add("Period: " + (dto.periodLabel() != null ? dto.periodLabel() : "All periods"));
        if (dto.baseCurrency() != null) {
            lines.add("Currency: " + dto.baseCurrency());
        }

        // Say it on the page, not just in a green banner on a screen nobody printed. A close pack
        // that carries an out-of-balance trial balance without saying so is a filed error.
        BigDecimal diff = difference(dto);
        lines.add(diff.signum() == 0
                ? "Balanced: total debits equal total credits."
                : "OUT OF BALANCE by " + fmtAmt(diff.abs())
                        + " - investigate before closing the period.");
        return lines;
    }

    /** Total debits less total credits; zero on a correct set of books. */
    private static BigDecimal difference(TrialBalanceDto dto) {
        BigDecimal debits  = dto.totalDebits()  != null ? dto.totalDebits()  : BigDecimal.ZERO;
        BigDecimal credits = dto.totalCredits() != null ? dto.totalCredits() : BigDecimal.ZERO;
        return debits.subtract(credits);
    }

    private void addIfPresent(List<String> lines, String value) {
        if (value != null && !value.isBlank()) {
            lines.add(value);
        }
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
     * One side of a row. A zero debit on a credit-balance account prints BLANK, the way a trial
     * balance has always been laid out — a column of "0.00" beside the real figures is noise the eye
     * has to filter before it can add anything up. The totals row never blanks: a zero total is the
     * answer, not an absence.
     */
    private String fmtSide(BigDecimal amt) {
        return amt == null || amt.signum() == 0 ? "" : fmtAmt(amt);
    }

    /**
     * Locale.US explicitly: the grouping/decimal separators end up inside a PDF built with a Cp1252
     * font, and a JVM default locale on a server is not something a printed statement should depend
     * on. Negative amounts keep an ASCII '-' (never U+2212).
     */
    private String fmtAmt(BigDecimal amt) {
        return amt != null ? String.format(Locale.US, "%,.2f", amt) : "";
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
