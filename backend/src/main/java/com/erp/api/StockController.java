package com.erp.api;

import com.erp.modules.stock.domain.dto.AdjustStockRequest;
import com.erp.modules.stock.domain.dto.OpeningBalanceRequest;
import com.erp.modules.stock.domain.dto.SetReorderLevelRequest;
import com.erp.modules.stock.domain.dto.StockMovementDto;
import com.erp.modules.stock.domain.dto.StockOnHandDto;
import com.erp.modules.stock.service.StockService;
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
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock REST surface — manual movement endpoints + on-hand reads (ADR-0010 D-11).
 *
 * <p>Event-driven movements (GOODS_RECEIPT, SALE_ISSUE, reversals) have NO REST endpoint
 * (FR-STOCK-15): they are driven by the outbox dispatcher. Only manual ops are here.
 *
 * <p>Permission gates:
 * <ul>
 *   <li>STOCK.VIEW — on-hand list, movement ledger.</li>
 *   <li>STOCK.ADJUST — manual adjustment, reorder-level set.</li>
 *   <li>STOCK.OPENING — opening balance.</li>
 * </ul>
 * All responses are auto-wrapped in {@code ApiResponse<T>} by {@link com.erp.platform.common.api.ApiResponseAdvice}.
 */
@RestController
@RequestMapping("/api/v1/stock")
public class StockController {

    private final StockService stockService;

    public StockController(StockService stockService) {
        this.stockService = stockService;
    }

    // -------------------------------------------------------------------------
    // Manual writes
    // -------------------------------------------------------------------------

    /**
     * POST /api/v1/stock/adjustments — manual ADJUSTMENT for a stockable product at the active branch.
     * reason is mandatory (BR-STOCK-05). Branch from RequestContext.
     */
    @PostMapping("/adjustments")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.productUid(), 'product', 'STOCK.ADJUST')")
    public StockMovementDto adjust(@Valid @RequestBody AdjustStockRequest request) {
        return stockService.adjust(request);
    }

    /**
     * POST /api/v1/stock/opening-balances — seed an OPENING_BALANCE for a never-tracked product.
     * Rejected if any prior movement exists at the active branch.
     */
    @PostMapping("/opening-balances")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.productUid(), 'product', 'STOCK.OPENING')")
    public StockMovementDto openingBalance(@Valid @RequestBody OpeningBalanceRequest request) {
        return stockService.openingBalance(request);
    }

    /**
     * PUT /api/v1/stock/on-hand/uid/{uid}/reorder-level — set or clear the reorder-level indicator.
     */
    @PutMapping("/on-hand/uid/{uid}/reorder-level")
    @PreAuthorize("@perm.scoped(#uid, 'stockonhand', 'STOCK.ADJUST')")
    public StockOnHandDto setReorderLevel(@PathVariable String uid,
                                          @RequestBody SetReorderLevelRequest request) {
        return stockService.setReorderLevel(uid, request);
    }

    // -------------------------------------------------------------------------
    // Reads — return ApiResponse<List<T>> with PageMeta (ProductController pattern)
    // -------------------------------------------------------------------------

    /**
     * GET /api/v1/stock/on-hand — paged on-hand list at the caller's active branch.
     * Returns derived negative/low flags on each row.
     */
    @GetMapping("/on-hand")
    @PreAuthorize("@perm.has('STOCK.VIEW')")
    public ApiResponse<List<StockOnHandDto>> listOnHand(Pageable pageable) {
        Page<StockOnHandDto> page = stockService.listOnHand(pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /**
     * GET /api/v1/stock/products/uid/{productUid}/movements — chronological movement ledger for a
     * product at the caller's active branch.
     */
    @GetMapping("/products/uid/{productUid}/movements")
    @PreAuthorize("@perm.has('STOCK.VIEW')")
    public ApiResponse<List<StockMovementDto>> listMovements(@PathVariable String productUid,
                                                              Pageable pageable) {
        Page<StockMovementDto> page = stockService.listMovements(productUid, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
