package com.erp.api;

import static com.erp.modules.reporting.export.ReportExportFormat.amount;
import static com.erp.modules.reporting.export.ReportExportFormat.text;

import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.ReportExportFormat;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.sales.domain.dto.PaymentSummaryReportDto;
import com.erp.modules.sales.domain.dto.PaymentSummaryRowDto;
import com.erp.modules.sales.domain.dto.PaymentSummaryTotalsDto;
import com.erp.modules.sales.service.PaymentSummaryReportQuery;
import com.erp.platform.security.RequestContext;
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
 * Daily cash-up / Payment Summary — money taken for finalised sales per day, cashier and payment
 * method.
 *
 * <p>Gated {@code POS.CASHUP.VIEW}, a managers' code (owner ruling 2026-10-02). It is deliberately
 * NOT {@code POS.SESSION.VIEW}: a till's X/Z-read shows one session, but this report shows every
 * cashier's takings company-wide, and CASHIER holds {@code POS.SESSION.VIEW}. Rules live in
 * {@link PaymentSummaryReportQuery}; this controller only flattens for export.
 */
@RestController
@RequestMapping("/api/v1/reports/payment-summary")
public class PaymentSummaryReportController {

    private final PaymentSummaryReportQuery query;
    private final TabularExporter           exporter;

    public PaymentSummaryReportController(PaymentSummaryReportQuery query,
                                          TabularExporter exporter) {
        this.query    = query;
        this.exporter = exporter;
    }

    @GetMapping
    @PreAuthorize("@perm.has('POS.CASHUP.VIEW')")
    public PaymentSummaryReportDto paymentSummary(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String cashierUid) {
        return query.report(RequestContext.get().companyId(), fromDate, toDate,
                branchUid, cashierUid);
    }

    /** On-screen gate AND {@code REPORT.EXPORT}, as on every report download. */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('POS.CASHUP.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportPaymentSummary(
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate fromDate,
            @RequestParam @DateTimeFormat(iso = ISO.DATE) LocalDate toDate,
            @RequestParam(required = false) String branchUid,
            @RequestParam(required = false) String cashierUid,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        PaymentSummaryReportDto dto = query.report(RequestContext.get().companyId(),
                fromDate, toDate, branchUid, cashierUid);
        return download(exporter.export(flatten(dto), format));
    }

    // -------------------------------------------------------------------------

    private TabularRenderModel flatten(PaymentSummaryReportDto dto) {
        List<String> headerLines = new ArrayList<>(ReportExportFormat.companyLines(dto.company()));
        headerLines.add("From " + dto.fromDate() + " To " + dto.toDate());
        headerLines.add("Branch: " + (dto.branchName() != null
                ? dto.branchName() : "All branches (whole company)"));
        headerLines.add("Cashier: " + (dto.cashierName() != null
                ? dto.cashierName() : "All cashiers"));
        headerLines.add("Payments on finalised sales, net of change given. Receivables receipts, "
                + "payouts and the opening float are not included.");

        List<Column> columns = List.of(
                new Column("Date", Align.LEFT),
                new Column("Cashier", Align.LEFT),
                new Column("Currency", Align.LEFT),
                new Column("Cash", Align.RIGHT),
                new Column("Mobile Money", Align.RIGHT),
                new Column("Card", Align.RIGHT),
                new Column("Cheque", Align.RIGHT),
                new Column("Total", Align.RIGHT),
                new Column("Payments", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>(dto.rows().size() + dto.totals().size());
        for (PaymentSummaryRowDto r : dto.rows()) {
            rows.add(List.of(
                    text(r.date()),
                    text(r.cashierName()),
                    text(r.currency()),
                    amount(r.cash()),
                    amount(r.mobileMoney()),
                    amount(r.card()),
                    amount(r.cheque()),
                    amount(r.total()),
                    String.valueOf(r.payments())));
        }

        // The base currency is the foot; any other currency's total prints as its own body line
        // just above it, so two currencies are never added into one figure.
        List<PaymentSummaryTotalsDto> totals = dto.totals();
        for (int i = 1; i < totals.size(); i++) {
            rows.add(totalsLine(totals.get(i)));
        }
        List<String> totalsRow = totals.isEmpty() ? null : totalsLine(totals.get(0));

        return new TabularRenderModel("Payment Summary", headerLines, dto.generatedAt(),
                columns, rows, totalsRow);
    }

    private static List<String> totalsLine(PaymentSummaryTotalsDto t) {
        return List.of(
                "TOTAL", "", text(t.currency()),
                amount(t.cash()),
                amount(t.mobileMoney()),
                amount(t.card()),
                amount(t.cheque()),
                amount(t.total()),
                String.valueOf(t.payments()));
    }

    private static ResponseEntity<byte[]> download(ExportResult result) {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename=\"" + result.filename() + "\"")
                .body(result.content());
    }
}
