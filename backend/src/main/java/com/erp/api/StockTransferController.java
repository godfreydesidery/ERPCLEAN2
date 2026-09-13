package com.erp.api;

import com.erp.modules.reporting.domain.dto.ReportCompanyHeaderDto;
import com.erp.modules.reporting.domain.enums.ExportFormat;
import com.erp.modules.reporting.export.ExportResult;
import com.erp.modules.reporting.export.TabularExporter;
import com.erp.modules.reporting.export.TabularRenderModel;
import com.erp.modules.reporting.export.TabularRenderModel.Align;
import com.erp.modules.reporting.export.TabularRenderModel.Column;
import com.erp.modules.reporting.service.ReportCompanyHeaderQuery;
import com.erp.modules.stock.domain.dto.CreateStockTransferRequest;
import com.erp.modules.stock.domain.dto.StockTransferDto;
import com.erp.modules.stock.domain.dto.StockTransferLineDto;
import com.erp.modules.stock.service.StockTransferService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import com.erp.modules.documents.domain.dto.DocumentBrandingDto;
import com.erp.modules.documents.service.DocumentBrandingService;
import com.erp.platform.security.RequestContext;
import java.util.ArrayList;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inter-location stock transfer REST surface (ADR-0028 D-5, FR-INVD-08..11).
 *
 * <p>Both transfer modes work between any two locations in the company, in the same branch or in
 * different branches. INSTANT is the one-sided mode — the sender completes it alone, so it is the
 * mode to use when the destination is a shop that is not on the system (no destination user, no
 * confirmation step). IN_TRANSIT is the two-sided mode where the destination confirms receipt.
 *
 * <p>Permission gates:
 * <ul>
 *   <li>STOCK.TRANSFER.VIEW — read</li>
 *   <li>STOCK.TRANSFER.CREATE — create / dispatch / instant-complete</li>
 *   <li>STOCK.TRANSFER.RECEIVE — receive</li>
 * </ul>
 *
 * <p>All responses are auto-wrapped in {@code ApiResponse<T>} by
 * {@link com.erp.platform.common.api.ApiResponseAdvice}.
 */
@RestController
@RequestMapping("/api/v1/stock-transfers")
public class StockTransferController {

    /** Date and time on the print footprint, matching the client's own documents (30-Aug-2026). */
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd-MMM-yyyy");
    private static final DateTimeFormatter TIME_FMT = DateTimeFormatter.ofPattern("h:mm:ss a");

    private final StockTransferService transferService;
    private final DocumentBrandingService branding;
    private final TabularExporter exporter;
    private final ReportCompanyHeaderQuery companyHeaderQuery;

    public StockTransferController(StockTransferService transferService,
                                   TabularExporter exporter,
                                   ReportCompanyHeaderQuery companyHeaderQuery,
                                   DocumentBrandingService branding) {
        this.transferService    = transferService;
        this.exporter           = exporter;
        this.companyHeaderQuery = companyHeaderQuery;
        this.branding           = branding;
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('STOCK.TRANSFER.CREATE')")
    public StockTransferDto create(@Valid @RequestBody CreateStockTransferRequest request) {
        return transferService.create(request);
    }

    /**
     * Complete an INSTANT transfer: DRAFT → COMPLETED in one TX (no in-transit leg, no confirmation
     * from the destination). Works across branches as well as within one — the destination branch
     * needs no user account, which is exactly what an off-system shop looks like.
     */
    @PatchMapping("/uid/{uid}/complete-instant")
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.CREATE')")
    public StockTransferDto completeInstant(@PathVariable String uid) {
        return transferService.completeInstant(uid);
    }

    /** Dispatch to in-transit: DRAFT → DISPATCHED, publishes STOCK.TRANSFER.DISPATCHED. */
    @PatchMapping("/uid/{uid}/dispatch")
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.CREATE')")
    public StockTransferDto dispatch(@PathVariable String uid) {
        return transferService.dispatch(uid);
    }

    /** Receive from in-transit: DISPATCHED → RECEIVED, publishes STOCK.TRANSFER.RECEIVED. */
    @PatchMapping("/uid/{uid}/receive")
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.RECEIVE')")
    public StockTransferDto receive(@PathVariable String uid) {
        return transferService.receive(uid);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.CREATE')")
    public void cancel(@PathVariable String uid) {
        transferService.cancel(uid);
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.VIEW')")
    public StockTransferDto getByUid(@PathVariable String uid) {
        return transferService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('STOCK.TRANSFER.VIEW')")
    public ApiResponse<List<StockTransferDto>> list(Pageable pageable) {
        Page<StockTransferDto> page = transferService.list(pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    // -------------------------------------------------------------------------
    // Export / print
    // -------------------------------------------------------------------------

    /**
     * The transfer as a printable document — PDF to send with the goods, XLSX or CSV to work with.
     *
     * <p>Gated on the transfer's own scope check <em>and</em> {@code REPORT.EXPORT}: a download
     * leaves the system, so it must never be reachable by a caller the screen itself refuses.
     */
    @GetMapping("/uid/{uid}/export")
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.VIEW') and @perm.has('REPORT.EXPORT')")
    public ResponseEntity<byte[]> export(@PathVariable String uid,
                                         @RequestParam(defaultValue = "PDF") ExportFormat format) {
        StockTransferDto dto = transferService.getByUid(uid);
        ReportCompanyHeaderDto company = companyHeaderQuery.forCompany(dto.companyId());
        ExportResult result = exporter.export(flatten(dto, company, logoOf(dto.companyId())), format);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(result.contentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(result.filename()).build().toString())
                .body(result.content());
    }

    /**
     * Lays the transfer out as a document rather than a bare table: who is sending, who is
     * receiving, when, and in which mode — then the goods. A storekeeper hands this to a driver,
     * so the two locations and the date matter as much as the line items.
     */
    /**
     * The company's logo, or null when it has never uploaded one — in which case the document
     * prints text-only, which is what it did before. A branding lookup must never be the reason a
     * storekeeper cannot print a transfer note, so any failure degrades to no logo.
     */
    private String logoOf(Long companyId) {
        try {
            DocumentBrandingDto b = branding.getForCompany(companyId);
            return b != null ? b.logoDataUri() : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private TabularRenderModel flatten(StockTransferDto dto, ReportCompanyHeaderDto company,
                                       String logoDataUri) {
        List<String> headerLines = new ArrayList<>();
        if (company != null && company.name() != null) {
            headerLines.add(company.name());
        }
        headerLines.add("Transfer No: " + nullToEmpty(dto.transferNumber()));
        headerLines.add("Date: " + (dto.transferDate() != null ? dto.transferDate().toString() : ""));
        headerLines.add("From: " + describeEnd(dto.sourceBranchName(), dto.sourceLocationName()));
        headerLines.add("To: " + describeEnd(dto.destBranchName(), dto.destLocationName()));
        headerLines.add("Status: " + (dto.status() != null ? dto.status().name() : "")
                + "   Mode: " + (dto.transferMode() != null ? dto.transferMode().name() : ""));
        if (dto.expectedArrivalDate() != null) {
            headerLines.add("Expected arrival: " + dto.expectedArrivalDate());
        }
        if (dto.notes() != null && !dto.notes().isBlank()) {
            headerLines.add("Notes: " + dto.notes());
        }

        // Columns carry what the client's own note carries, in their words: Package is the unit a
        // line is counted in, and Price is the cost of ONE of them — the figure that was missing.
        // The title stays "Stock Transfer" rather than their "Inter-Branch Transfer": these move
        // between LOCATIONS, which are often inside one branch, and the narrower name would be a
        // lie on most of them.
        List<Column> columns = List.of(
                new Column("Code", Align.LEFT),
                new Column("Product Description", Align.LEFT),
                new Column("Package", Align.LEFT),
                new Column("Qty", Align.RIGHT),
                new Column("Price", Align.RIGHT),
                new Column("Total Amount", Align.RIGHT));

        List<StockTransferLineDto> lines = dto.lines() != null ? dto.lines() : List.of();
        List<List<String>> rows = new ArrayList<>(lines.size());
        BigDecimal totalQty   = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        boolean anyValued     = false;
        int unvalued          = 0;
        for (StockTransferLineDto l : lines) {
            rows.add(List.of(
                    nullToEmpty(l.productCode()),
                    nullToEmpty(l.productName()),
                    nullToEmpty(l.unitName()),
                    fmtQty(l.qtyTransferred()),
                    fmtMoney(l.unitCost()),
                    fmtMoney(l.valueAmount())));
            if (l.qtyTransferred() != null) {
                totalQty = totalQty.add(l.qtyTransferred());
            }
            if (l.valueAmount() != null) {
                totalValue = totalValue.add(l.valueAmount());
                anyValued = true;
            } else {
                unvalued++;
            }
        }

        // Value used to be omitted on the grounds that a document travelling with the goods should
        // not show internal cost to whoever receives them. That reasoning does not survive contact
        // with who actually receives a transfer: both ends are the company's own store, and the
        // person signing for the goods is the one who has to account for them. The client asked for
        // the amount and the total, and they are the only reader. (Kilimanjaro 2026-09-13.)
        //
        // An uncosted line prints blank, never 0.00, and the foot says how many it left out — a
        // total that silently drops them would understate what is moving.
        List<String> totalsRow = List.of(
                "", "Total Amount", "", fmtQty(totalQty), "",
                anyValued ? fmtMoney(totalValue) : "");

        // The foot carries INFORMATION only. No sign-off rules and no Net/Vat/Rounding block: the
        // client's own note has them, but a transfer charges nobody, so a "Vat Amount: 0.00" line
        // would be a tax statement about a movement that has no tax in it.
        List<String> footerLines = new ArrayList<>();
        if (unvalued > 0) {
            footerLines.add("Note: " + unvalued + " of " + lines.size()
                    + (lines.size() == 1 ? " line has" : " lines have")
                    + " no cost on record, and " + (unvalued == 1 ? "is" : "are")
                    + " left out of the total value.");
        }
        ZonedDateTime now = ZonedDateTime.now();
        footerLines.add(printFootprint(company, now));

        // generatedAt is rendered as "Generated: <value>" at the head. It gets the plain timestamp;
        // the full "Printed On / At / By / From" line belongs at the FOOT, where the client's own
        // note carries it. Passing the footprint to both printed "Generated: Printed On: ..." twice
        // over — which is what reading the rendered PDF, rather than trusting it, turned up.
        return new TabularRenderModel("Stock Transfer", headerLines,
                now.format(DATE_FMT) + " " + now.format(TIME_FMT),
                columns, rows, totalsRow, footerLines, logoDataUri);
    }

    /**
     * Who produced this document and when — the client's note carries it and theirs does not, which
     * is how an argument about which copy is current gets settled.
     *
     * <p>The name is the caller's username: it is what {@code RequestContext} carries, it is what
     * the person signs the paper with, and resolving a display name here would mean a user lookup on
     * every export for a line of text.
     */
    private String printFootprint(ReportCompanyHeaderDto company, ZonedDateTime now) {
        RequestContext.Principal p = RequestContext.get();
        StringBuilder sb = new StringBuilder()
                .append("Printed On: ").append(now.format(DATE_FMT))
                .append("    Printed At: ").append(now.format(TIME_FMT));
        if (p != null && p.username() != null && !p.username().isBlank()) {
            sb.append("    Printed By: ").append(p.username());
        }
        if (company != null && company.name() != null && !company.name().isBlank()) {
            sb.append("    Printed From: ").append(company.name());
        }
        return sb.toString();
    }

    private String describeEnd(String branchName, String locationName) {
        String branch = nullToEmpty(branchName);
        String location = nullToEmpty(locationName);
        if (branch.isEmpty()) {
            return location;
        }
        return location.isEmpty() ? branch : branch + " — " + location;
    }

    private String nullToEmpty(String s) {
        return s != null ? s : "";
    }

    private String fmtQty(BigDecimal qty) {
        return qty != null ? qty.stripTrailingZeros().toPlainString() : "";
    }

    /**
     * Money at 2 dp. A null is an UNKNOWN cost, not a free one, so it prints blank — a 0.00 in a
     * value column tells whoever signs for the goods that they are worth nothing.
     */
    private String fmtMoney(BigDecimal amount) {
        return amount != null
                ? amount.setScale(2, java.math.RoundingMode.HALF_UP).toPlainString()
                : "";
    }
}
