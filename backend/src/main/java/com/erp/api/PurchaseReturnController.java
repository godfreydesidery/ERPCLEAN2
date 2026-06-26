package com.erp.api;

import com.erp.modules.purchases.domain.dto.CreatePurchaseReturnRequest;
import com.erp.modules.purchases.domain.dto.PurchaseReturnDto;
import com.erp.modules.purchases.service.PurchaseReturnService;
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
 * Purchase Return REST controller (ADR-0027 D-7, FR-PROC-19..22).
 */
@RestController
@RequestMapping("/api/v1/purchase-returns")
public class PurchaseReturnController {

    private final PurchaseReturnService service;

    public PurchaseReturnController(PurchaseReturnService service) {
        this.service = service;
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
}
