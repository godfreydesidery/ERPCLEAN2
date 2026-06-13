package com.erp.api;

import com.erp.modules.fx.domain.dto.CurrencyDto;
import com.erp.modules.fx.domain.dto.CurrencyRateDto;
import com.erp.modules.fx.domain.dto.UpsertRateRequest;
import com.erp.platform.common.money.FxRateService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * FX currency master + exchange-rate maintenance (ADR-0036 D-7).
 *
 * <p>Permissions: {@code CURRENCY.VIEW} for reads, {@code CURRENCY.MANAGE} for writes.
 * Every response is auto-wrapped in {@code ApiResponse<T>} by the response-advice.
 * Long ids serialise as JSON strings via the global Jackson config.
 */
@RestController
@RequestMapping("/api/v1/fx")
public class CurrencyController {

    private final FxRateService fxRateService;

    public CurrencyController(FxRateService fxRateService) {
        this.fxRateService = fxRateService;
    }

    // ── Currency master ───────────────────────────────────────────────────────

    /** List all active currencies (global reference data). */
    @GetMapping("/currencies")
    @PreAuthorize("@perm.has('CURRENCY.VIEW')")
    public List<CurrencyDto> listCurrencies() {
        return fxRateService.listActiveCurrencies();
    }

    /** Get a single currency by uid. */
    @GetMapping("/currencies/uid/{uid}")
    @PreAuthorize("@perm.has('CURRENCY.VIEW')")
    public CurrencyDto getCurrency(@PathVariable String uid) {
        return fxRateService.getCurrencyByUid(uid);
    }

    // ── Currency rates ────────────────────────────────────────────────────────

    /**
     * Add a new effective-dated rate row for a company.
     * No edit-in-place — a correction is always a new row.
     */
    // @perm.has (not @perm.scoped): @perm.scoped expects a company UID String, but UpsertRateRequest
    // carries the numeric companyId (Long) — passing it bricked the endpoint (403) for every non-root
    // CURRENCY.MANAGE holder. FxRateServiceImpl.addRate independently enforces the company scope via
    // ScopeGuard.assertCanActIn(req.companyId()) (the correct numeric-id check), so the gate here is
    // the role/permission check only. (FX adversarial-review HIGH.)
    @PostMapping("/rates")
    @PreAuthorize("@perm.has('CURRENCY.MANAGE')")
    public CurrencyRateDto addRate(@Valid @RequestBody UpsertRateRequest req) {
        return fxRateService.addRate(req);
    }

    /**
     * List rates for a company, newest-first (paginated).
     * The effective-dated history grid per D-7: "newest-first; no edit-in-place."
     */
    @GetMapping("/rates")
    @PreAuthorize("@perm.has('CURRENCY.VIEW')")
    public Page<CurrencyRateDto> listRates(
            @RequestParam Long companyId,
            @PageableDefault(size = 50, sort = "effectiveDate") Pageable pageable) {
        return fxRateService.listRates(companyId, pageable);
    }

    /** Get a single rate row by uid. */
    @GetMapping("/rates/uid/{uid}")
    @PreAuthorize("@perm.has('CURRENCY.VIEW')")
    public CurrencyRateDto getRate(@PathVariable String uid) {
        return fxRateService.getRateByUid(uid);
    }
}
