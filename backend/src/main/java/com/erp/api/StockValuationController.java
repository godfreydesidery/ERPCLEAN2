package com.erp.api;

import com.erp.modules.stock.domain.dto.OpeningValuationResultDto;
import com.erp.modules.stock.domain.dto.SetOpeningValuationRequest;
import com.erp.modules.stock.domain.dto.StockValuationReportDto;
import com.erp.modules.stock.service.InventoryValuationService;
import com.erp.modules.stock.service.StockValuationQuery;
import com.erp.platform.security.RequestContext;
import jakarta.validation.Valid;
import java.time.Clock;
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
    private final Clock                     clock;

    public StockValuationController(StockValuationQuery valuationQuery,
                                     InventoryValuationService valuationService,
                                     Clock clock) {
        this.valuationQuery   = valuationQuery;
        this.valuationService = valuationService;
        this.clock            = clock;
    }

    /**
     * GET /api/v1/stock/valuation/report
     *
     * <p>Returns per-product on-hand quantities, avg costs, values and a GL reconciliation bar
     * (Σ on_hand_value vs GL 1300 balance). Branch-scoped to the caller's active branch context;
     * the report aggregates across branches within the company unless the query narrows further.
     *
     * <p><strong>{@code asOf} is no longer silently ignored.</strong> It used to be bound, parsed,
     * and then dropped — a user asking for 30 June got today's numbers with a 200 OK and no hint
     * that the date had not been applied. The query reads {@code stock_on_hand}, which is a
     * maintained CURRENT projection, so TODAY is the only date it can honestly answer; a past date
     * is rejected with a 400 that points at the period-windowed Stock Movement report instead.
     *
     * <p>A FUTURE date is rejected for the same reason and used not to be: it returned today's
     * numbers with a 200, so a report headed "as at 31 Dec 2027" was really "as at today" — the
     * identical silent-lie the past-date guard was added to stop. Nothing can be known about stock
     * that has not moved yet, so there is no honest answer to give.
     *
     * <p>A back-dated valuation cannot simply be replayed from {@code stock_movements} either:
     * opening-valuation and landed-cost paths adjust {@code on_hand_value} without writing a
     * correspondingly valued movement row, so Σ {@code value_amount} up to a date is not the
     * on-hand value at that date. Real point-in-time valuation needs a costed-history model — that
     * is a design change, not a parameter.
     */
    @GetMapping("/report")
    @PreAuthorize("@perm.has('INVENTORY.VALUATION.VIEW')")
    public StockValuationReportDto report(
            @RequestParam(name = "asOf", required = false) LocalDate asOf) {
        LocalDate today = LocalDate.now(clock);
        if (asOf != null && asOf.isBefore(today)) {
            throw new IllegalArgumentException(
                    "Stock valuation is only available for the current position. "
                    + "To see stock as at an earlier date, use the Stock Movement report.");
        }
        if (asOf != null && asOf.isAfter(today)) {
            throw new IllegalArgumentException(
                    "Stock valuation is only available for the current position. "
                    + "A future date cannot be reported on — leave the date blank for today's "
                    + "stock position.");
        }
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
