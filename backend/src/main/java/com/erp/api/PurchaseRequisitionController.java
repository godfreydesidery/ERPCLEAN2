package com.erp.api;

import com.erp.modules.purchases.domain.dto.CreatePurchaseRequisitionRequest;
import com.erp.modules.purchases.domain.dto.PurchaseRequisitionDto;
import com.erp.modules.purchases.service.PurchaseRequisitionService;
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
 * Purchase Requisition REST controller (ADR-0027 D-1, FR-PROC-01..04).
 */
@RestController
@RequestMapping("/api/v1/purchase-requisitions")
public class PurchaseRequisitionController {

    private final PurchaseRequisitionService service;

    public PurchaseRequisitionController(PurchaseRequisitionService service) {
        this.service = service;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid(), 'company', 'PURCHASE.REQUISITION.CREATE')")
    public ApiResponse<PurchaseRequisitionDto> create(
            @RequestBody CreatePurchaseRequisitionRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'purchaserequisition', 'PURCHASE.REQUISITION.VIEW')")
    public ApiResponse<PurchaseRequisitionDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(service.getByUid(uid));
    }

    @GetMapping
    @PreAuthorize("@perm.has('PURCHASE.REQUISITION.VIEW')")
    public ApiResponse<List<PurchaseRequisitionDto>> list(
            @RequestParam Long companyId,
            Pageable pageable) {
        Page<PurchaseRequisitionDto> page = service.list(companyId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** Submit for approval (DRAFT → SUBMITTED). */
    @PostMapping("/uid/{uid}/submit")
    @PreAuthorize("@perm.scoped(#uid, 'purchaserequisition', 'PURCHASE.REQUISITION.CREATE')")
    public ApiResponse<PurchaseRequisitionDto> submit(@PathVariable String uid) {
        return ApiResponse.ok(service.submit(uid));
    }

    /** Approve (SUBMITTED → APPROVED). */
    @PostMapping("/uid/{uid}/approve")
    @PreAuthorize("@perm.scoped(#uid, 'purchaserequisition', 'PURCHASE.REQUISITION.APPROVE')")
    public ApiResponse<PurchaseRequisitionDto> approve(@PathVariable String uid) {
        return ApiResponse.ok(service.approve(uid));
    }

    /** Reject (SUBMITTED → REJECTED). */
    @PostMapping("/uid/{uid}/reject")
    @PreAuthorize("@perm.scoped(#uid, 'purchaserequisition', 'PURCHASE.REQUISITION.APPROVE')")
    public ApiResponse<PurchaseRequisitionDto> reject(
            @PathVariable String uid,
            @RequestParam(required = false, defaultValue = "") String reason) {
        return ApiResponse.ok(service.reject(uid, reason));
    }

    /** Convert to RFQ or PO (APPROVED → CONVERTED). Returns uid of the created document. */
    @PostMapping("/uid/{uid}/convert")
    @PreAuthorize("@perm.scoped(#uid, 'purchaserequisition', 'PURCHASE.REQUISITION.APPROVE')")
    public ApiResponse<String> convert(
            @PathVariable String uid,
            @RequestParam String targetType) {
        return ApiResponse.ok(service.convert(uid, targetType));
    }

    /** Cancel (any non-final state → CANCELLED). */
    @PostMapping("/uid/{uid}/cancel")
    @PreAuthorize("@perm.scoped(#uid, 'purchaserequisition', 'PURCHASE.REQUISITION.CREATE')")
    public ApiResponse<PurchaseRequisitionDto> cancel(
            @PathVariable String uid,
            @RequestParam(required = false, defaultValue = "") String reason) {
        return ApiResponse.ok(service.cancel(uid, reason));
    }
}
