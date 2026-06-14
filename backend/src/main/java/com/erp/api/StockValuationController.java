package com.erp.api;

import com.erp.modules.stock.domain.dto.OpeningValuationResultDto;
import com.erp.modules.stock.domain.dto.SetOpeningValuationRequest;
import com.erp.modules.stock.domain.dto.StockValuationReportDto;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockValuationQuery;
import com.erp.platform.security.RequestContext;
import jakarta.validation.Valid;
import java.time.LocalDate;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inventory valuation REST surface (ADR-0020 D-6/D-5b).
 *
 * <p>Endpoints:
 * <ul>
 *   <li>GET  /api/v1/stock/valuation/report — stock valuation report + GL recon bar
 *       (INVENTORY.VALUATION.VIEW).</li>
 *   <li>POST /api/v1/stock/valuation/opening — set opening valuation for an on-hand row
 *       (INVENTORY.OPENING.SET).</li>
 * </ul>
 *
 * <p>All responses are auto-wrapped in {@code ApiResponse<T>} by
 * {@link com.erp.platform.common.api.ApiResponseAdvice}.
 */
@RestController
@RequestMapping("/api/v1/stock/valuation")
public class StockValuationController {

    private final StockValuationQuery       valuationQuery;
    private final InventoryValuationService valuationService;

    public StockValuationController(StockValuationQuery valuationQuery,
                                     InventoryValuationService valuationService) {
        this.valuationQuery   = valuationQuery;
        this.valuationService = valuationService;
    }

    /**
     * GET /api/v1/stock/valuation/report
     *
     * <p>Returns per-product on-hand quantities, avg costs, values and a GL reconciliation bar
     * (Σ on_hand_value vs GL 1300 balance). Branch-scoped to the caller's active branch context;
     * the report aggregates across branches within the company unless the query narrows further.
     *
     * <p>Optional {@code asOf} date parameter is reserved for future point-in-time reporting;
     * currently ignored by the query (real-time balance is returned).
     */
    @GetMapping("/report")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW')")
    public StockValuationReportDto report(
            @RequestParam(name = "asOf", required = false) LocalDate asOf) {
        RequestContext.Principal principal = RequestContext.get();
        return valuationQuery.report(principal.companyId());
    }

    /**
     * POST /api/v1/stock/valuation/opening
     *
     * <p>Sets the opening cost (avg_cost + on_hand_value) for an existing quantity-only on-hand row
     * and posts DR INVENTORY / CR OPENING_BALANCE_EQUITY. One-time per on-hand row — rejected if
     * already valued (avg_cost IS NOT NULL or on_hand_value != 0).
     *
     * <p>Posting date defaults to today when not supplied in the request body.
     */
    @PostMapping("/opening")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('INVENTORY.OPENING.SET')")
    public OpeningValuationResultDto setOpening(
            @Valid @RequestBody SetOpeningValuationRequest request) {
        return valuationService.setOpeningValue(request, LocalDate.now());
    }
}
