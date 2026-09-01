package com.erp.api;

import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.DirectGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.PurchaseCostSuggestionDto;
import com.erp.modules.purchases.domain.dto.VoidGoodsReceiptRequest;
import com.erp.modules.purchases.service.DirectGoodsReceiptService;
import com.erp.modules.purchases.service.GoodsReceiptService;
import com.erp.modules.purchases.service.PurchaseCostSuggestionService;
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
 * Goods Receipt lifecycle REST controller (ADR-0011, FR-PURCH-01b/07/08/09).
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Permission codes seeded in V8__purchases.sql (ADR-0011 D-11/D-12).
 */
@RestController
@RequestMapping("/api/v1/goods-receipts")
public class GoodsReceiptController {

    private final GoodsReceiptService service;
    private final DirectGoodsReceiptService directService;
    private final PurchaseCostSuggestionService costSuggestions;

    public GoodsReceiptController(GoodsReceiptService service,
                                   DirectGoodsReceiptService directService,
                                   PurchaseCostSuggestionService costSuggestions) {
        this.service = service;
        this.directService = directService;
        this.costSuggestions = costSuggestions;
    }

    /**
     * Create a GR against a PO and immediately receive it.
     * Authority derives from the PO's company (purchaseorder target type, ADR-0011 D-12).
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.purchaseOrderUid(), 'purchaseorder', 'PURCHASE.RECEIVE')")
    public ApiResponse<GoodsReceiptDto> createAndReceive(@RequestBody CreateGoodsReceiptRequest req) {
        return ApiResponse.ok(service.createAndReceive(req));
    }

    /**
     * Receive goods with NO prior LPO — a walk-in / cash purchase (K3, Kilimanjaro 2026-08-08).
     * The service auto-raises the backing purchase order and receives against it atomically.
     *
     * <p><b>Permission.</b> {@code PURCHASE.RECEIVE.DIRECT} — its own verb, seeded in
     * {@code R__seed_permissions.sql} and granted to STOREKEEPER. It is deliberately NOT
     * {@code PURCHASE.RECEIVE}: that verb means "receive against an order a buyer already raised and
     * priced", whereas this one also authors the order and its costs in one unreviewed step, which is
     * a materially different authority. Holding {@code PURCHASE.RECEIVE} alone therefore no longer
     * reaches this endpoint. The {@code scoped} form with the {@code company} target type matches
     * {@code PurchaseOrderController#create} — the write is company-scoped from the body's companyUid.
     */
    @PostMapping("/direct")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#req.companyUid(), 'company', 'PURCHASE.RECEIVE.DIRECT')")
    public ApiResponse<GoodsReceiptDto> receiveDirect(@RequestBody DirectGoodsReceiptRequest req) {
        return ApiResponse.ok(directService.receiveDirect(req));
    }

    /**
     * Suggested unit cost for an item the storekeeper is about to add to a direct receipt — the
     * last quote from this supplier, then the last actual purchase from them, then the product's
     * cost price (K-2026-08-30 #4: the cost was being retyped on every delivery).
     *
     * <p>Gated on {@code PURCHASE.RECEIVE.DIRECT} rather than the PO path's
     * {@code PURCHASE.ORDER.CREATE}: a storekeeper holds the former and not the latter, so reusing
     * the purchase-order endpoint here would 403 exactly the person the suggestion is for.
     *
     * <p>Returns {@code null} data when no source has a price — the caller leaves the cost blank
     * rather than defaulting to zero.
     */
    @GetMapping("/direct/cost-suggestion")
    @PreAuthorize("@perm.scoped(#companyUid, 'company', 'PURCHASE.RECEIVE.DIRECT')")
    public ApiResponse<PurchaseCostSuggestionDto> directCostSuggestion(
            @RequestParam String companyUid,
            @RequestParam(required = false) String supplierUid,
            @RequestParam String productUid,
            @RequestParam String unitUid) {
        return ApiResponse.ok(costSuggestions
                .suggestUnitCostForDirectReceipt(companyUid, supplierUid, productUid, unitUid)
                .orElse(null));
    }

    /** Get GR by uid. */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'goodsreceipt', 'PURCHASE.GOODS_RECEIPT.VIEW')")
    public ApiResponse<GoodsReceiptDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(service.getByUid(uid));
    }

    /** Paged list / search for a company. */
    @GetMapping
    @PreAuthorize("@perm.has('PURCHASE.GOODS_RECEIPT.VIEW')")
    public ApiResponse<List<GoodsReceiptDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) String q,
            Pageable pageable) {
        Page<GoodsReceiptDto> page = service.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** Void a RECEIVED GR: reverses PO outstanding + emits STOCK.RECEIPT.VOIDED. */
    @PostMapping("/uid/{uid}/void")
    @PreAuthorize("@perm.scoped(#uid, 'goodsreceipt', 'PURCHASE.VOID')")
    public ApiResponse<GoodsReceiptDto> voidReceipt(@PathVariable String uid,
                                                     @RequestBody VoidGoodsReceiptRequest req) {
        return ApiResponse.ok(service.voidReceipt(uid, req));
    }
}
