package com.erp.api;

import static com.erp.api.ExportLetterhead.fmtAmt;
import static com.erp.api.ExportLetterhead.nullToEmpty;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.tax.domain.dto.FileVatReturnRequest;
import com.erp.modules.tax.domain.dto.OpenVatReturnRequest;
import com.erp.modules.tax.domain.dto.VatReturnBandDto;
import com.erp.modules.tax.domain.dto.VatReturnDto;
import com.erp.modules.tax.domain.enums.VatReturnStatus;
import com.erp.modules.tax.service.VatReturnService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * VAT return lifecycle endpoints (ADR-0017 D-4, FR-VAT-01/02/08).
 * Permissions seeded in V14: VAT.RETURN.PREPARE, VAT.RETURN.FILE, VAT.VIEW.
 */
@RestController
@RequestMapping("/api/v1/vat/returns")
public class VatReturnController {

    private final VatReturnService service;
    private final TabularExporter  exporter;
    private final ExportLetterhead letterhead;

    public VatReturnController(VatReturnService service, TabularExporter exporter,
                               ExportLetterhead letterhead) {
        this.service    = service;
        this.exporter   = exporter;
        this.letterhead = letterhead;
    }

    /** Open a new DRAFT VAT return for the given company-month (FR-VAT-01). */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid,'company','VAT.RETURN.PREPARE')")
    public VatReturnDto open(@Valid @RequestBody OpenVatReturnRequest req) {
        return service.open(req);
    }

    /** Recompute a DRAFT return (FR-VAT-02). */
    @PostMapping("/uid/{uid}/recompute")
    @PreAuthorize("@perm.scoped(#uid,'vatreturn','VAT.RETURN.PREPARE')")
    public VatReturnDto recompute(@PathVariable String uid) {
        return service.recompute(uid);
    }

    /** File (lock) a DRAFT return — freeze + GL settlement (FR-VAT-08). */
    @PostMapping("/uid/{uid}/file")
    @PreAuthorize("@perm.scoped(#uid,'vatreturn','VAT.RETURN.FILE')")
    public VatReturnDto file(@PathVariable String uid,
                              @Valid @RequestBody FileVatReturnRequest req) {
        return service.file(uid, req);
    }

    /** Single return by uid. */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'vatreturn','VAT.VIEW')")
    public VatReturnDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    /**
     * The return face as a document — the rate bands and the summary boxes exactly as the detail
     * screen shows them, under the company letterhead with its TIN and VRN. Same gate as the screen
     * plus {@code REPORT.EXPORT}. A DRAFT prints marked as such, so it cannot pass for a filed return.
     */
    @GetMapping("/uid/{uid}/export")
    @PreAuthorize("@perm.scoped(#uid,'vatreturn','VAT.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> export(@PathVariable String uid,
                                         @RequestParam(defaultValue = "PDF") ExportFormat format) {
        VatReturnDto dto = service.getByUid(uid);
        ExportLetterhead.Letterhead head = letterhead.forCompany(dto.companyId());
        return ExportLetterhead.download(exporter.export(
                flatten(dto, head, letterhead.now(dto.companyId())), format));
    }

    /** Paged list by company. */
    @GetMapping
    @PreAuthorize("@perm.has('VAT.VIEW')")
    public ApiResponse<List<VatReturnDto>> list(@RequestParam Long companyId, Pageable pageable) {
        Page<VatReturnDto> page = service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    // -------------------------------------------------------------------------

    static TabularRenderModel flatten(VatReturnDto r, ExportLetterhead.Letterhead head,
                                      ZonedDateTime now) {
        ReportCompanyHeaderDto company = head != null ? head.company() : null;
        List<String> headerLines = new ArrayList<>(ExportLetterhead.companyLines(company));
        headerLines.add("Return No: " + nullToEmpty(r.returnNumber())
                + (r.amendment() ? "  (amended return)" : ""));
        headerLines.add("Period: " + r.periodStart() + " to " + r.periodEnd()
                + "    Due: " + (r.dueDate() != null ? r.dueDate().toString() : ""));
        if (r.status() == VatReturnStatus.FILED) {
            headerLines.add("Status: FILED on "
                    + (r.filingDate() != null ? r.filingDate().toString() : "")
                    + "    TRA reference: " + nullToEmpty(r.filingReference()));
        } else {
            headerLines.add("Status: DRAFT — not yet filed; figures may still change");
        }

        List<Column> columns = List.of(
                new Column("Item", Align.LEFT),
                new Column("Taxable Value", Align.RIGHT),
                new Column("VAT", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>();
        rows.add(List.of("Supplies by tax band", "", ""));
        List<VatReturnBandDto> bands = r.bands() != null ? r.bands() : List.of();
        if (bands.isEmpty()) {
            rows.add(List.of("  No supplies in this period", "", ""));
        }
        for (VatReturnBandDto b : bands) {
            rows.add(List.of("  " + bandLabel(b.taxBand()), fmtAmt(b.taxableBase()),
                    fmtAmt(b.outputVat())));
        }
        rows.add(List.of("Total sales turnover / output VAT", fmtAmt(r.salesTurnover()),
                fmtAmt(r.outputVat())));
        rows.add(List.of("  of which zero-rated sales", fmtAmt(r.zeroRatedSales()), ""));
        rows.add(List.of("  of which exempt sales", fmtAmt(r.exemptSales()), ""));
        // Purchases turnover is not computed on every return; blank, never 0.00, when it is not.
        rows.add(List.of("Purchases turnover", fmtAmt(r.purchasesTurnover()), ""));
        rows.add(List.of("Less: input VAT (deductible)", "", bracketed(r.inputVat())));
        rows.add(List.of("Add / (less): adjustments", "", fmtAmt(r.adjustmentsTotal())));
        rows.add(List.of("Less: credit brought forward", "", bracketed(r.openingCredit())));

        List<String> totalsRow = List.of("Net VAT — " + netLabel(r.netVat()), "",
                fmtAmt(r.netVat()));

        List<String> footer = new ArrayList<>();
        if (r.closingCredit() != null && r.closingCredit().signum() > 0) {
            footer.add("Credit carried forward to next period: " + fmtAmt(r.closingCredit()));
        }
        if (nonZero(r.penaltyAmount()) || nonZero(r.interestAmount())) {
            footer.add("Penalty: " + fmtAmt(orZero(r.penaltyAmount()))
                    + "    Interest: " + fmtAmt(orZero(r.interestAmount()))
                    + "  (not included in Net VAT above)");
        }
        if (r.paidAt() != null) {
            footer.add("Paid: " + fmtAmt(r.paidAmount())
                    + (r.paymentReference() != null ? "    Ref: " + r.paymentReference() : ""));
        }
        footer.add(ExportLetterhead.printFootprint(company, now));

        return new TabularRenderModel("VAT Return " + nullToEmpty(r.returnNumber()), headerLines,
                ExportLetterhead.generatedAt(now), columns, rows, totalsRow, footer,
                head != null ? head.logoDataUri() : null);
    }

    static String netLabel(BigDecimal net) {
        if (net == null || net.abs().compareTo(new BigDecimal("0.001")) <= 0) {
            return "Nil";
        }
        return net.signum() > 0 ? "Payable to TRA" : "Credit carried forward";
    }

    private static String bandLabel(String band) {
        if (band == null) {
            return "";
        }
        return switch (band) {
            case "STANDARD"   -> "Standard rated";
            case "ZERO_RATED" -> "Zero rated";
            case "EXEMPT"     -> "Exempt";
            default           -> band.replace('_', ' ');
        };
    }

    private static String bracketed(BigDecimal v) {
        return v == null ? "" : "(" + fmtAmt(v) + ")";
    }

    private static boolean nonZero(BigDecimal v) {
        return v != null && v.signum() != 0;
    }

    private static BigDecimal orZero(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }
}
