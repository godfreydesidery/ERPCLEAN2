package com.erp.api;

import com.erp.modules.purchases.domain.dto.CaptureSupplierQuoteRequest;
import com.erp.modules.purchases.domain.dto.SupplierQuoteDto;
import com.erp.modules.purchases.service.SupplierQuoteService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
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
 * Supplier Quote REST controller (ADR-0027 D-3, FR-PROC-09..12).
 */
@RestController
@RequestMapping("/api/v1/supplier-quotes")
public class SupplierQuoteController {

    private final SupplierQuoteService service;

    public SupplierQuoteController(SupplierQuoteService service) {
        this.service = service;
    }

    /** Capture a supplier's quote against an RFQ. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('PURCHASE.RFQ.MANAGE')")
    public ApiResponse<SupplierQuoteDto> capture(@RequestBody CaptureSupplierQuoteRequest req) {
        return ApiResponse.ok(service.capture(req));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'supplierquote', 'PURCHASE.RFQ.VIEW')")
    public ApiResponse<SupplierQuoteDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(service.getByUid(uid));
    }

    /** List all quotes for a given RFQ. */
    @GetMapping("/by-rfq/{rfqUid}")
    @PreAuthorize("@perm.has('PURCHASE.RFQ.VIEW')")
    public ApiResponse<List<SupplierQuoteDto>> listByRfq(
            @PathVariable String rfqUid,
            Pageable pageable) {
        Page<SupplierQuoteDto> page = service.listByRfq(rfqUid, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** Last quoted unit cost for a product/supplier pair (for price reference). */
    @GetMapping("/last-quoted-cost")
    @PreAuthorize("@perm.has('PURCHASE.RFQ.VIEW')")
    public ApiResponse<Object> lastQuotedCost(
            @RequestParam Long companyId,
            @RequestParam Long supplierId,
            @RequestParam Long productId) {
        return ApiResponse.ok(service.lastQuotedUnitCost(companyId, supplierId, productId));
    }
}
