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

    private final StockTransferService transferService;
    private final TabularExporter exporter;
    private final ReportCompanyHeaderQuery companyHeaderQuery;

    public StockTransferController(StockTransferService transferService,
                                   TabularExporter exporter,
                                   ReportCompanyHeaderQuery companyHeaderQuery) {
        this.transferService    = transferService;
        this.exporter           = exporter;
        this.companyHeaderQuery = companyHeaderQuery;
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
        ExportResult result = exporter.export(flatten(dto, company), format);
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
    private TabularRenderModel flatten(StockTransferDto dto, ReportCompanyHeaderDto company) {
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

        List<Column> columns = List.of(
                new Column("Code", Align.LEFT),
                new Column("Description", Align.LEFT),
                new Column("Unit", Align.LEFT),
                new Column("Qty", Align.RIGHT));

        List<StockTransferLineDto> lines = dto.lines() != null ? dto.lines() : List.of();
        List<List<String>> rows = new ArrayList<>(lines.size());
        BigDecimal totalQty = BigDecimal.ZERO;
        for (StockTransferLineDto l : lines) {
            rows.add(List.of(
                    nullToEmpty(l.productCode()),
                    nullToEmpty(l.productName()),
                    nullToEmpty(l.unitName()),
                    fmtQty(l.qtyTransferred())));
            if (l.qtyTransferred() != null) {
                totalQty = totalQty.add(l.qtyTransferred());
            }
        }

        // Value is deliberately absent. A transfer moves stock at cost between two of the
        // company's own locations, so a money column on a document that travels with the goods
        // would put internal cost in front of whoever receives them, and it settles nothing.
        List<String> totalsRow = List.of("", "TOTAL", "", fmtQty(totalQty));

        return new TabularRenderModel("Stock Transfer", headerLines,
                Instant.now().toString(), columns, rows, totalsRow);
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
}
