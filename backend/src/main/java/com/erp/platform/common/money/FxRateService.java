package com.erp.platform.common.money;

import com.erp.modules.fx.domain.dto.CurrencyDto;
import com.erp.modules.fx.domain.dto.CurrencyRateDto;
import com.erp.modules.fx.domain.dto.UpsertRateRequest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * CRUD on currency_rates + the rate-lookup primitive consumed by
 * {@link CurrencyConversionService} (ADR-0036 D-1/D-2).
 *
 * <p>All write operations require {@code CURRENCY.MANAGE}; reads require {@code CURRENCY.VIEW}.
 * {@code assertCanActIn} is called on every method.
 */
public interface FxRateService {

    // ── Currency master (read-only in v1; managed by migration seed) ─────────

    /** List all active currencies (global, no tenant filter). */
    List<CurrencyDto> listActiveCurrencies();

    /** Get a single currency by uid. */
    CurrencyDto getCurrencyByUid(String uid);

    // ── Currency rates ────────────────────────────────────────────────────────

    /**
     * Insert a new effective-dated rate row.
     *
     * <p>A new row is always inserted (never update-in-place — corrections are new rows, per D-2).
     * Validates: rate &gt; 0; from/to codes active in currencies; to == company base currency in v1.
     */
    CurrencyRateDto addRate(UpsertRateRequest req);

    /**
     * List rates for a company, newest-first (paginated).
     * {@code assertCanActIn(companyId)} on every call.
     */
    Page<CurrencyRateDto> listRates(Long companyId, Pageable pageable);

    /** Get a single rate by uid. */
    CurrencyRateDto getRateByUid(String uid);

    /**
     * Look up the effective rate on or before {@code asOf} for (company, fromCurrency, toCurrency).
     * Uses {@code rateType = "SPOT"} (the v1 default).
     *
     * <p>Returns the rate value, or throws {@link FxRateNotFoundException} if no active row
     * resolves. This is the primitive called by {@link CurrencyConversionService}.
     *
     * @param companyId    tenant company
     * @param fromCurrency foreign currency code (ISO 4217)
     * @param toCurrency   base currency code (ISO 4217)
     * @param asOf         as-of date (inclusive upper bound on effective_date)
     * @return the resolved rate (units of toCurrency per 1 unit of fromCurrency)
     * @throws FxRateNotFoundException if no rate resolves
     */
    BigDecimal rateOn(Long companyId, String fromCurrency, String toCurrency, LocalDate asOf);
}
