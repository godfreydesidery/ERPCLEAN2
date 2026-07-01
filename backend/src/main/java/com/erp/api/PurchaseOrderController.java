package com.erp.api;

import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.ApprovePoRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.UpdatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.VoidPurchaseOrderRequest;
import com.erp.modules.purchases.service.PurchaseOrderService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Purchase Order lifecycle REST controller (ADR-0011, FR-PURCH-01a..12).
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Permission codes seeded in V8__purchases.sql (ADR-0011 D-11/D-12).
 */
@RestController
@RequestMapping("/api/v1/purchase-orders")
public class PurchaseOrderController {

    private final PurchaseOrderService service;

    public PurchaseOrderController(PurchaseOrderService service) {
        this.service = service;
    }

    /** Create a DRAFT PO. companyUid in body; branch from RequestContext. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid(), 'company', 'PURCHASE.ORDER.CREATE')")
    public ApiResponse<PurchaseOrderDto> create(@RequestBody CreatePurchaseOrderRequest req) {
        return ApiResponse.ok(service.create(req));
    }

    /**
     * Get PO by uid. Readable by the purchasing viewer (PURCHASE.ORDER.VIEW) OR a storekeeper
     * receiving against it (PURCHASE.RECEIVE) — the goods-receipt-create screen loads the chosen
     * PO header here before recording the receipt. Both branches scope-check the loaded PO's
     * company via {@code @perm.scoped}, so a PURCHASE.RECEIVE holder reads only their OWN company's
     * PO (read-gate widening only, tenant scope unchanged).
     */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.VIEW') "
            + "or @perm.scoped(#uid, 'purchaseorder', 'PURCHASE.RECEIVE')")
    public ApiResponse<PurchaseOrderDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(service.getByUid(uid));
    }

    /**
     * Paged list / search for a company. POs are transactional documents (amounts, suppliers), so —
     * unlike the pure reference-data pickers — this list is NOT opened to plain company membership.
     * It additionally accepts the adjacent transactional verbs whose own screens fire this read:
     * <ul>
     *   <li>{@code AP.BILL.ENTER} — the supplier-bill entry screen three-way-matches a bill against
     *       an ORDERED PO (sim 2026-06-28, ISSUE-002).</li>
     *   <li>{@code PURCHASE.RECEIVE} — the goods-receipt-create screen's PO picker needs to find the
     *       open POs the storekeeper receives against; the GR write itself is already gated on
     *       PURCHASE.RECEIVE, so the read-dependency is owned by the same verb (sim 2026-06-28,
     *       goods-receipt read-closure / F21-family).</li>
     * </ul>
     * The service applies its own company-scope predicate ({@code assertCanActIn}), so a holder of
     * any of these verbs reads only their OWN company's POs — this widens the permission gate, not
     * the tenant scope.
     */
    @GetMapping
    @PreAuthorize("@perm.has('PURCHASE.ORDER.VIEW') or @perm.has('AP.BILL.ENTER') or @perm.has('PURCHASE.RECEIVE')")
    public ApiResponse<List<PurchaseOrderDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) String q,
            Pageable pageable) {
        Page<PurchaseOrderDto> page = service.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** Update header fields (while DRAFT). */
    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.CREATE')")
    public ApiResponse<PurchaseOrderDto> update(@PathVariable String uid,
                                                 @RequestBody UpdatePurchaseOrderRequest req) {
        return ApiResponse.ok(service.update(uid, req));
    }

    /** Add a line (DRAFT only). */
    @PostMapping("/uid/{uid}/lines")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.CREATE')")
    public ApiResponse<PurchaseOrderLineDto> addLine(@PathVariable String uid,
                                                      @RequestBody AddPurchaseOrderLineRequest req) {
        return ApiResponse.ok(service.addLine(uid, req));
    }

    /** Update a line (DRAFT only). */
    @PutMapping("/uid/{uid}/lines/{lineUid}")
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.CREATE')")
    public ApiResponse<PurchaseOrderLineDto> updateLine(@PathVariable String uid,
                                                         @PathVariable String lineUid,
                                                         @RequestBody UpdatePurchaseOrderLineRequest req) {
        return ApiResponse.ok(service.updateLine(uid, lineUid, req));
    }

    /** Remove a line (DRAFT only). */
    @DeleteMapping("/uid/{uid}/lines/{lineUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.CREATE')")
    public void removeLine(@PathVariable String uid, @PathVariable String lineUid) {
        service.removeLine(uid, lineUid);
    }

    /**
     * List all lines for a PO. Readable by the purchasing viewer (PURCHASE.ORDER.VIEW) OR a
     * storekeeper receiving against it (PURCHASE.RECEIVE) — the goods-receipt-create screen loads
     * the chosen PO's lines here to render the receive grid. Both branches scope-check the loaded
     * PO's company via {@code @perm.scoped}, so the receive read stays within the caller's own
     * company (read-gate widening only).
     */
    @GetMapping("/uid/{uid}/lines")
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.VIEW') "
            + "or @perm.scoped(#uid, 'purchaseorder', 'PURCHASE.RECEIVE')")
    public ApiResponse<List<PurchaseOrderLineDto>> listLines(@PathVariable String uid) {
        return ApiResponse.ok(service.listLines(uid));
    }

    /** Place the order: DRAFT → ORDERED; assigns PO-####. */
    @PostMapping("/uid/{uid}/place")
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.CREATE')")
    public ApiResponse<PurchaseOrderDto> placeOrder(@PathVariable String uid) {
        return ApiResponse.ok(service.placeOrder(uid));
    }

    /** Close the order: {ORDERED, PARTIALLY_RECEIVED, RECEIVED} → CLOSED. */
    @PostMapping("/uid/{uid}/close")
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.CREATE')")
    public ApiResponse<PurchaseOrderDto> closeOrder(@PathVariable String uid) {
        return ApiResponse.ok(service.closeOrder(uid));
    }

    /** Void the order. */
    @PostMapping("/uid/{uid}/void")
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.VOID')")
    public ApiResponse<PurchaseOrderDto> voidOrder(@PathVariable String uid,
                                                    @RequestBody VoidPurchaseOrderRequest req) {
        return ApiResponse.ok(service.voidOrder(uid, req));
    }

    /**
     * APPROVALS-047: Submit a DRAFT PO to the approval engine.
     * Creates the approval_request row and transitions approval_status → PENDING (or APPROVED
     * when the engine auto-approves per policy). The PO stays DRAFT until /place is called.
     */
    @PostMapping("/uid/{uid}/submit-for-approval")
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.CREATE')")
    public ApiResponse<PurchaseOrderDto> submitForApproval(@PathVariable String uid) {
        return ApiResponse.ok(service.submitForApproval(uid));
    }

    /** Approve a PO that is pending approval (ADR-0027 D-6). */
    @PostMapping("/uid/{uid}/approve")
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.APPROVE')")
    public ApiResponse<PurchaseOrderDto> approvePo(@PathVariable String uid,
                                                    @RequestBody ApprovePoRequest req) {
        return ApiResponse.ok(service.approvePo(uid, req));
    }

    /** Reject a PO that is pending approval (ADR-0027 D-6). */
    @PostMapping("/uid/{uid}/reject")
    @PreAuthorize("@perm.scoped(#uid, 'purchaseorder', 'PURCHASE.ORDER.APPROVE')")
    public ApiResponse<PurchaseOrderDto> rejectPo(@PathVariable String uid,
                                                   @RequestBody ApprovePoRequest req) {
        return ApiResponse.ok(service.rejectPo(uid, req));
    }

    /** Create a PO from an awarded supplier quote (ADR-0027 D-4). */
    @PostMapping("/from-quote/{quoteUid}")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('PURCHASE.ORDER.CREATE')")
    public ApiResponse<PurchaseOrderDto> createFromQuote(@PathVariable String quoteUid) {
        return ApiResponse.ok(service.createFromQuote(quoteUid));
    }
}
