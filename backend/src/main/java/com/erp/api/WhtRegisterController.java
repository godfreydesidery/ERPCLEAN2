package com.erp.api;

import static com.erp.api.ExportLetterhead.fmtAmt;
import static com.erp.api.ExportLetterhead.nullToEmpty;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.tax.domain.dto.WhtPaymentResultDto;
import com.erp.modules.tax.domain.dto.WhtPeriodPaymentRequest;
import com.erp.modules.tax.domain.dto.WhtRegisterDto;
import com.erp.modules.tax.domain.dto.WhtRegisterRowDto;
import com.erp.modules.tax.domain.dto.WhtRemitRequest;
import com.erp.modules.tax.service.WhtRegisterService;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * WHT period register query (ADR-0017 D-9, FR-WHT-04).
 * Accepts either year+month or explicit periodStart+periodEnd date range.
 * Permission seeded in V14: WHT.VIEW (the export additionally REPORT.EXPORT).
 */
@RestController
@RequestMapping("/api/v1/wht/register")
public class WhtRegisterController {

    private final WhtRegisterService service;
    private final TabularExporter    exporter;
    private final ExportLetterhead   letterhead;

    public WhtRegisterController(WhtRegisterService service, TabularExporter exporter,
                                 ExportLetterhead letterhead) {
        this.service    = service;
        this.exporter   = exporter;
        this.letterhead = letterhead;
    }

    /**
     * Get the WHT register for a company in a period.
     * Accepts year+month (resolved to first/last of month) OR explicit periodStart+periodEnd.
     * If year+month provided, periodStart/periodEnd are ignored.
     */
    @GetMapping
    @PreAuthorize("@perm.has('WHT.VIEW')")
    public WhtRegisterDto getRegister(
            @RequestParam Long companyId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) LocalDate periodStart,
            @RequestParam(required = false) LocalDate periodEnd) {
        LocalDate[] period = resolvePeriod(year, month, periodStart, periodEnd);
        return service.getRegister(companyId, period[0], period[1]);
    }

    /**
     * The register as a document: WHT deducted from suppliers (payable to TRA) and WHT deducted by
     * customers (receivable), each with its subtotal, under the company letterhead. Same params and
     * gate as the screen, plus {@code REPORT.EXPORT}.
     */
    @GetMapping("/export")
    @PreAuthorize("@perm.has('WHT.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> exportRegister(
            @RequestParam Long companyId,
            @RequestParam(required = false) Integer year,
            @RequestParam(required = false) Integer month,
            @RequestParam(required = false) LocalDate periodStart,
            @RequestParam(required = false) LocalDate periodEnd,
            @RequestParam(defaultValue = "PDF") ExportFormat format) {
        LocalDate[] period = resolvePeriod(year, month, periodStart, periodEnd);
        WhtRegisterDto dto = service.getRegister(companyId, period[0], period[1]);
        ExportLetterhead.Letterhead head = letterhead.forCompany(companyId);
        return ExportLetterhead.download(exporter.export(
                flatten(dto, head, ZonedDateTime.now()), format));
    }

    /**
     * Mark a WHT transaction as remitted to the tax authority (ADR-0040 D-7).
     * Permission WHT.REMIT seeded in the repeatable permission seed.
     */
    @PostMapping("/transactions/{uid}/remit")
    @PreAuthorize("@perm.has('WHT.REMIT')")
    public WhtPaymentResultDto remit(@PathVariable String uid, @RequestBody @Valid WhtRemitRequest req) {
        // ACC-07: with a cashBankAccountUid the remittance is also booked (DR WHT Payable / CR bank).
        return service.remit(uid, req);
    }

    /**
     * ACC-07: pay all WHT deducted from suppliers in the period (not yet remitted) to TRA in one
     * cash/bank payment — DR WHT Payable / CR the account — and mark every certificate remitted.
     */
    @PostMapping("/payments")
    @PreAuthorize("@perm.has('WHT.REMIT')")
    public WhtPaymentResultDto payPeriod(@RequestBody @Valid WhtPeriodPaymentRequest req) {
        return service.payPeriod(req);
    }

    // -------------------------------------------------------------------------

    private static LocalDate[] resolvePeriod(Integer year, Integer month,
                                             LocalDate periodStart, LocalDate periodEnd) {
        if (year != null && month != null) {
            YearMonth ym = YearMonth.of(year, month);
            return new LocalDate[] {ym.atDay(1), ym.atEndOfMonth()};
        } else if (periodStart != null && periodEnd != null) {
            return new LocalDate[] {periodStart, periodEnd};
        }
        throw new IllegalArgumentException(
                "Provide either year+month or periodStart+periodEnd.");
    }

    static TabularRenderModel flatten(WhtRegisterDto dto, ExportLetterhead.Letterhead head,
                                      ZonedDateTime now) {
        ReportCompanyHeaderDto company = head != null ? head.company() : null;
        List<String> headerLines = new ArrayList<>(ExportLetterhead.companyLines(company));
        headerLines.add("Period: " + dto.periodStart() + " to " + dto.periodEnd());

        List<Column> columns = List.of(
                new Column("Certificate No", Align.LEFT),
                new Column("Date", Align.LEFT),
                new Column("Party", Align.LEFT),
                new Column("Source Ref", Align.LEFT),
                new Column("Taxable Base", Align.RIGHT),
                new Column("WHT Amount", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>();
        section(rows, "WHT PAYABLE TO TRA (deducted from supplier payments)",
                dto.payableRows(), dto.totalPayable(), "Total payable");
        section(rows, "WHT RECEIVABLE (deducted by customers from their payments)",
                dto.receivableRows(), dto.totalReceivable(), "Total receivable");

        List<String> footer = new ArrayList<>();
        footer.add(ExportLetterhead.printFootprint(company, now));

        // No grand total: payable and receivable are different obligations; adding them is meaningless.
        return new TabularRenderModel("WHT Register", headerLines,
                ExportLetterhead.generatedAt(now), columns, rows, null, footer,
                head != null ? head.logoDataUri() : null);
    }

    private static void section(List<List<String>> rows, String title, List<WhtRegisterRowDto> lines,
                                BigDecimal total, String totalLabel) {
        rows.add(List.of(title, "", "", "", "", ""));
        List<WhtRegisterRowDto> safe = lines != null ? lines : List.of();
        if (safe.isEmpty()) {
            rows.add(List.of("No certificates in this period", "", "", "", "", ""));
        }
        BigDecimal base = BigDecimal.ZERO;
        for (WhtRegisterRowDto r : safe) {
            if (r.taxableBase() != null) {
                base = base.add(r.taxableBase());
            }
            rows.add(List.of(
                    nullToEmpty(r.whtNumber()),
                    r.certificateDate() != null ? r.certificateDate().toString() : "",
                    nullToEmpty(r.partyName()),
                    nullToEmpty(r.sourceRef()),
                    fmtAmt(r.taxableBase()),
                    fmtAmt(r.whtAmount())));
        }
        rows.add(List.of("", "", totalLabel + " (" + safe.size() + ")", "",
                fmtAmt(base), fmtAmt(total != null ? total : BigDecimal.ZERO)));
    }
}
