package com.erp.api;

import static com.erp.api.ExportLetterhead.fmtAmt;
import static com.erp.api.ExportLetterhead.nullToEmpty;

import com.erp.modules.documents.service.DocumentRenderService;
import com.erp.modules.purchases.domain.dto.CreatePurchaseReturnRequest;
import com.erp.modules.purchases.domain.dto.PurchaseReturnDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintLineDto;
import com.erp.modules.purchases.service.PurchaseReturnService;
import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.ZoneId;
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
 * Purchase Return REST controller (ADR-0027 D-7, FR-PROC-19..22).
 */
@RestController
@RequestMapping("/api/v1/purchase-returns")
public class PurchaseReturnController {

    private final PurchaseReturnService service;
    private final DocumentRenderService documents;
    private final TabularExporter       exporter;
    private final ExportLetterhead      letterhead;

    public PurchaseReturnController(PurchaseReturnService service,
                                    DocumentRenderService documents,
                                    TabularExporter exporter,
                                    ExportLetterhead letterhead) {
        this.service    = service;
        this.documents  = documents;
        this.exporter   = exporter;
        this.letterhead = letterhead;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid(), 'company', 'PURCHASE.RETURN.CREATE')")
    public ApiResponse<PurchaseReturnDto> create(@Valid @RequestBody CreatePurchaseReturnRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'purchasereturn', 'PURCHASE.RETURN.VIEW')")
    public ApiResponse<PurchaseReturnDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(service.getByUid(uid));
    }

    @GetMapping
    @PreAuthorize("@perm.has('PURCHASE.RETURN.VIEW')")
    public ApiResponse<List<PurchaseReturnDto>> list(
            @RequestParam Long companyId,
            Pageable pageable) {
        Page<PurchaseReturnDto> page = service.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** Confirm: DRAFT → CONFIRMED; publishes PURCHASE.RETURNED outbox event. */
    @PostMapping("/uid/{uid}/confirm")
    @PreAuthorize("@perm.scoped(#uid, 'purchasereturn', 'PURCHASE.RETURN.CREATE')")
    public ApiResponse<PurchaseReturnDto> confirm(@PathVariable String uid) {
        return ApiResponse.ok(service.confirm(uid));
    }

    /**
     * Print / export the purchase return as a supplier-facing document (Kilimanjaro: "cannot export
     * or print purchase return"). {@code PDF} is the same branded layout as the printed GRN;
     * {@code XLSX} / {@code CSV} are the same content as a sheet, under the company letterhead.
     *
     * <p><b>Stream-only.</b> Rendered on request and returned as the response body — nothing is
     * written: no {@code generated_documents} row, no outbox event, no schema change.
     *
     * <p><b>Gate</b> = viewing the return ({@code PURCHASE.RETURN.VIEW}, tenant-scoped to the
     * return's company) AND the permission that gates printing the goods receipt it reverses
     * ({@code DOCUMENT.RENDER}). Every seeded role that can view a purchase return holds both.
     */
    @GetMapping("/uid/{uid}/export")
    @PreAuthorize("@perm.scoped(#uid, 'purchasereturn', 'PURCHASE.RETURN.VIEW') "
            + "and @perm.has('DOCUMENT.RENDER')")
    public ResponseEntity<byte[]> export(@PathVariable String uid,
                                         @RequestParam(defaultValue = "PDF") ExportFormat format) {
        PurchaseReturnPrintDto pr = service.printByUid(uid);
        if (format == ExportFormat.PDF) {
            return ExportLetterhead.download(new ExportResult(
                    documents.renderPurchaseReturn(pr), "application/pdf",
                    fileStem(pr) + ".pdf"));
        }
        ExportLetterhead.Letterhead head = letterhead.forCompany(pr.companyId());
        return ExportLetterhead.download(exporter.export(
                flatten(pr, head, ZonedDateTime.now(zoneOf(pr))), format));
    }

    // -------------------------------------------------------------------------

    /**
     * The Excel / CSV face of the note: letterhead, the return's identity block, the supplier, the
     * lines in each line's own unit, the totals, and the sign-off rules as footer lines.
     */
    static TabularRenderModel flatten(PurchaseReturnPrintDto pr, ExportLetterhead.Letterhead head,
                                      ZonedDateTime now) {
        ReportCompanyHeaderDto company = head != null ? head.company() : null;
        List<String> headerLines = new ArrayList<>(ExportLetterhead.companyLines(company));
        headerLines.add("Return No: " + nullToEmpty(pr.returnNumber())
                + "    Date: " + nullToEmpty(pr.returnDate())
                + "    Status: " + nullToEmpty(pr.status())
                + (pr.isDraft() ? " (not confirmed)" : ""));
        headerLines.add("GRN No: " + nullToEmpty(pr.goodsReceiptNumber())
                + "    PO No: " + nullToEmpty(pr.purchaseOrderNumber())
                + (pr.debitNoteNumber() != null ? "    Debit Note No: " + pr.debitNoteNumber() : ""));
        StringBuilder supplier = new StringBuilder("Supplier: ").append(nullToEmpty(pr.supplierName()));
        if (pr.supplierTin() != null) supplier.append("    TIN: ").append(pr.supplierTin());
        if (pr.supplierVrn() != null) supplier.append("    VRN: ").append(pr.supplierVrn());
        headerLines.add(supplier.toString());
        if (!pr.supplierAddressLines().isEmpty()) {
            headerLines.add(String.join(", ", pr.supplierAddressLines()));
        }
        headerLines.add("Reason: " + nullToEmpty(pr.reason()));
        if (pr.currency() != null) {
            headerLines.add("Currency: " + pr.currency());
        }

        List<Column> columns = List.of(
                new Column("#", Align.RIGHT),
                new Column("Code", Align.LEFT),
                new Column("Product", Align.LEFT),
                new Column("Unit", Align.LEFT),
                new Column("Qty", Align.RIGHT),
                new Column("Unit Cost", Align.RIGHT),
                new Column("Value", Align.RIGHT));

        List<List<String>> rows = new ArrayList<>();
        for (PurchaseReturnPrintLineDto l : pr.lines()) {
            rows.add(List.of(
                    String.valueOf(l.lineNo()),
                    nullToEmpty(l.productCode()),
                    nullToEmpty(l.productName()),
                    nullToEmpty(l.unitName()),
                    fmtQty(l.returnedQty()),
                    fmtAmt(l.unitCost()),
                    fmtAmt(l.lineValue())));
        }

        List<String> footer = new ArrayList<>();
        footer.add("Net Amount: " + fmtAmt(pr.netAmount()));
        if (pr.hasVat()) {
            footer.add("VAT Amount: " + fmtAmt(pr.vatAmount()));
        }
        footer.add("Total Amount: " + fmtAmt(pr.totalAmount()));
        footer.add("Prepared By: " + (pr.preparedByName() != null ? pr.preparedByName() : "")
                + " ____________________");
        footer.add("Received by Supplier: ____________________    Date: ____________");
        footer.add(ExportLetterhead.printFootprint(company, now));

        List<String> totalsRow = List.of("", "", "Total", "", "", "", fmtAmt(pr.totalAmount()));

        return new TabularRenderModel("Purchase Return " + nullToEmpty(pr.returnNumber()),
                headerLines, ExportLetterhead.generatedAt(now), columns, rows, totalsRow, footer,
                head != null ? head.logoDataUri() : null);
    }

    /** Quantity in the line's unit: no trailing zeros, no exponent ("2", "1.5", never "2.000000"). */
    static String fmtQty(BigDecimal qty) {
        if (qty == null) {
            return "";
        }
        BigDecimal q = qty.stripTrailingZeros();
        return (q.scale() < 0 ? q.setScale(0) : q).toPlainString();
    }

    private static String fileStem(PurchaseReturnPrintDto pr) {
        String number = pr.returnNumber() != null ? pr.returnNumber() : pr.uid();
        return "purchase-return-" + number.toLowerCase().replaceAll("[^a-z0-9]+", "-");
    }

    private static ZoneId zoneOf(PurchaseReturnPrintDto pr) {
        try {
            return pr.timeZone() != null ? ZoneId.of(pr.timeZone()) : ZoneId.systemDefault();
        } catch (RuntimeException e) {
            return ZoneId.systemDefault();
        }
    }
}
