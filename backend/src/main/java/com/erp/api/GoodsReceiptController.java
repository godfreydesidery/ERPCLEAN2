package com.erp.api;

import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.VoidGoodsReceiptRequest;
import com.erp.modules.purchases.service.GoodsReceiptService;
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

    public GoodsReceiptController(GoodsReceiptService service) {
        this.service = service;
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

    /** Get GR by uid. */
    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'goodsreceipt', 'PURCHASE.GOODS_RECEIPT.VIEW')")
    public ApiResponse<GoodsReceiptDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(service.getByUid(uid));
    }

    /** Paged list / search for a company. */
    @GetMapping
    @PreAuthorize("hasAuthority('PURCHASE.GOODS_RECEIPT.VIEW')")
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
