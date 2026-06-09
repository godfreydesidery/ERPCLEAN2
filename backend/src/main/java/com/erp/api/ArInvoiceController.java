package com.erp.api;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.service.ArInvoiceService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * AR open-item (invoice) read endpoints (ADR-0014, FR-AR-01/02).
 * Creation flows via the outbox handler (ArSalePostedHandler) — no POST here.
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Permission codes seeded in V11__accounts_receivable.sql: AR.VIEW, AR.INVOICE.VIEW.
 */
@RestController
@RequestMapping("/api/v1/ar/invoices")
public class ArInvoiceController {

    private final ArInvoiceService service;

    public ArInvoiceController(ArInvoiceService service) {
        this.service = service;
    }

    /**
     * Single open item by uid. Scope-checks the item's company against the caller's active scope.
     */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'arinvoice','AR.INVOICE.VIEW')")
    public ArInvoiceDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    /**
     * Paged list by company, with optional customer filter.
     * companyId is the numeric Long id (as used by the service layer).
     */
    @GetMapping
    @PreAuthorize("@perm.has('AR.VIEW')")
    public ApiResponse<List<ArInvoiceDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) Long customerId,
            Pageable pageable) {
        Page<ArInvoiceDto> page = customerId != null
                ? service.listByCustomer(companyId, customerId, pageable)
                : service.listByCompany(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
