package com.erp.api;

import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.ReallocateReceiptRequest;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.ReverseReceiptRequest;
import com.erp.modules.ar.service.ArReceiptService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * AR receipt (cash collection) endpoints (ADR-0014, FR-AR-06/07).
 * POST records and allocates in one atomic TX (GL post included — a GL failure rolls back).
 * Permission codes seeded in V11__accounts_receivable.sql: AR.RECEIPT.RECORD, AR.VIEW.
 */
@RestController
@RequestMapping("/api/v1/ar/receipts")
public class ArReceiptController {

    private final ArReceiptService service;

    public ArReceiptController(ArReceiptService service) {
        this.service = service;
    }

    /**
     * Record a receipt and auto-allocate (or apply manual allocation lines).
     * Scoped on the request body's companyUid — gates both the permission and tenant scope.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid,'company','AR.RECEIPT.RECORD')")
    public ArReceiptDto record(@Valid @RequestBody RecordReceiptRequest req) {
        return service.recordAndAllocate(req);
    }

    /**
     * Replace the receipt's allocation set (ARC-06): apply an advance or over-payment held on
     * account to invoices raised later. Posts nothing to the GL (BR-AR-12). Gated by the seeded
     * AR.RECEIPT.ALLOCATE ("allocate or re-allocate a receipt") or AR.RECEIPT.RECORD — every role
     * that can record a receipt can apply it — and scoped to the receipt's company.
     */
    @PutMapping("/uid/{uid}/allocations")
    @PreAuthorize("@perm.scoped(#uid,'arreceipt','AR.RECEIPT.ALLOCATE') "
            + "or @perm.scoped(#uid,'arreceipt','AR.RECEIPT.RECORD')")
    public ArReceiptDto reallocate(@PathVariable String uid,
                                   @Valid @RequestBody ReallocateReceiptRequest req) {
        return service.reallocate(uid, req.allocations());
    }

    /**
     * Reverse a posted receipt (ARC-04): a receipt keyed against the wrong customer, for the wrong
     * amount, or for money that never arrived. Posts the reversing journal, writes the opposite
     * cash-book row and restores the invoices it settled. Gated by AR.RECEIPT.REVERSE (finance
     * seats only) and scoped to the receipt's company. The reason is required.
     */
    @PostMapping("/uid/{uid}/reverse")
    @PreAuthorize("@perm.scoped(#uid,'arreceipt','AR.RECEIPT.REVERSE')")
    public ArReceiptDto reverse(@PathVariable String uid,
                                @Valid @RequestBody ReverseReceiptRequest req) {
        return service.reverse(uid, req.reason());
    }

    /** Single receipt by uid. */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'arreceipt','AR.VIEW')")
    public ArReceiptDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    /**
     * Paged list by company, with optional customer filter (ARC-02). companyId is the numeric Long
     * id; the customer may be named by {@code customerId} or {@code customerUid} (what the web
     * screen sends; resolved inside the company).
     */
    @GetMapping
    @PreAuthorize("@perm.has('AR.VIEW')")
    public ApiResponse<List<ArReceiptDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) String customerUid,
            Pageable pageable) {
        Page<ArReceiptDto> page = service.list(companyId, customerId, customerUid, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
