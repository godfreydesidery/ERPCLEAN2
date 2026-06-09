package com.erp.api;

import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
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

    /** Single receipt by uid. */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'arreceipt','AR.VIEW')")
    public ArReceiptDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    /**
     * Paged list by company, with optional customer filter.
     * companyId is the numeric Long id.
     */
    @GetMapping
    @PreAuthorize("@perm.has('AR.VIEW')")
    public ApiResponse<List<ArReceiptDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long customerId,
            Pageable pageable) {
        Page<ArReceiptDto> page = customerId != null
                ? service.listByCustomer(companyId, customerId, pageable)
                : service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
