package com.erp.api;

import com.erp.modules.stock.domain.dto.AdjustStockRequest;
import com.erp.modules.stock.domain.dto.LocationOnHandRowDto;
import com.erp.modules.stock.domain.dto.OpeningBalanceRequest;
import com.erp.modules.stock.domain.dto.SetReorderLevelRequest;
import com.erp.modules.stock.domain.dto.StockMovementDto;
import com.erp.modules.stock.domain.dto.StockOnHandDto;
import com.erp.modules.stock.service.LocationOnHandQuery;
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
import org.springframework.web.bind.annotation.RequestParam;
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

    private final StockService         stockService;
    private final LocationOnHandQuery  locationOnHandQuery;

    public StockController(StockService stockService,
                            LocationOnHandQuery locationOnHandQuery) {
        this.stockService        = stockService;
        this.locationOnHandQuery = locationOnHandQuery;
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
     *
     * <p>Scope (company + branch) is always taken from the request context — the active branch is
     * governed by the global branch switcher (X-Branch-Uid), never a request param. The optional
     * {@code q} filters within that scope by product code/name (case-insensitive contains).
     */
    @GetMapping("/on-hand")
    @PreAuthorize("@perm.has('STOCK.VIEW')")
    public ApiResponse<List<StockOnHandDto>> listOnHand(
            @RequestParam(required = false) String q,
            Pageable pageable) {
        Page<StockOnHandDto> page = stockService.listOnHand(q, pageable);
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

    // -------------------------------------------------------------------------
    // Per-location on-hand view (ADR-0028 D-8, FR-INVD-05/06)
    // -------------------------------------------------------------------------

    /**
     * GET /api/v1/stock/on-hand/by-location?companyId=&branchId= — paged per-location on-hand rows.
     * Requires STOCK.VIEW.
     */
    @GetMapping("/on-hand/by-location")
    @PreAuthorize("@perm.has('STOCK.VIEW')")
    public ApiResponse<List<LocationOnHandRowDto>> listOnHandByLocation(
            @RequestParam Long companyId,
            @RequestParam Long branchId,
            Pageable pageable) {
        Page<LocationOnHandRowDto> page =
                locationOnHandQuery.queryForBranch(companyId, branchId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /**
     * GET /api/v1/stock/on-hand/by-product/uid/{productUid}?companyId= — all locations holding the
     * product identified by uid. Requires STOCK.VIEW.
     */
    @GetMapping("/on-hand/by-product/uid/{productUid}")
    @PreAuthorize("@perm.has('STOCK.VIEW')")
    public List<LocationOnHandRowDto> listOnHandByProduct(
            @PathVariable String productUid,
            @RequestParam Long companyId) {
        return locationOnHandQuery.queryForProductByUid(companyId, productUid);
    }
}
