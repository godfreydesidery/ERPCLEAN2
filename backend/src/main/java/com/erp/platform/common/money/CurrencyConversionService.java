package com.erp.platform.common.money;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Cross-cutting FX conversion service (ADR-0036 D-1, ADR-0005 D-6/D-8).
 *
 * <p>Three rules (D-1) that protect the single-currency path and correctness:
 * <ol>
 *   <li><b>Identity short-circuit (the ONLY rate=1 path):</b> when {@code fromCode} equals the
 *       company base currency, return {@code ConvertedAmount(faceAmount, ONE, now())} with no
 *       rate-table lookup. This is the byte-identical single-currency fast path.</li>
 *   <li><b>Foreign conversion:</b> otherwise look up the effective rate via {@link FxRateService},
 *       compute {@code baseAmount = round(faceAmount × rate, baseMinorUnits)} HALF_UP (minor units
 *       from the {@code currencies} master), and stamp {@code rate}/{@code rateAt}.</li>
 *   <li><b>Loud failure:</b> if {@code fromCode != base} and no active rate resolves, throw
 *       {@link FxRateNotFoundException} — a foreign document must never silently post at par.
 *       The short-circuit applies strictly to {@code from==base}; it is never a catch-all
 *       default for a missing foreign row.</li>
 * </ol>
 *
 * <p>This service lives in {@code platform.common.money} (beside {@code Money}) so that every
 * module (GL, AR, AP, Sales) can consume it without crossing a {@code ModuleBoundaryTest} edge.
 */
public interface CurrencyConversionService {

    /**
     * Convert {@code faceAmount} from {@code fromCode} to the company's base currency.
     *
     * <p>Rate direction: {@code baseAmount = faceAmount × rate} where {@code rate} = units of
     * base per one unit of foreign. Rounding: HALF_UP to the base currency's minor units.
     *
     * @param faceAmount face amount in {@code fromCode}
     * @param fromCode   ISO 4217 source currency code
     * @param companyId  tenant company (determines base currency)
     * @param asOf       effective date for the rate lookup
     * @return {@link ConvertedAmount} with baseAmount, rate, rateAt
     * @throws FxRateNotFoundException if fromCode != base and no rate resolves
     */
    ConvertedAmount toBase(BigDecimal faceAmount, String fromCode, Long companyId, LocalDate asOf);

    /**
     * General-purpose convert between any two currencies for a company.
     *
     * <p>When {@code fromCode.equals(toCode)}, returns face unchanged with rate=1 (identity).
     * Otherwise resolves the effective rate and converts HALF_UP.
     *
     * @param faceAmount face amount in {@code fromCode}
     * @param fromCode   source ISO 4217 code
     * @param toCode     target ISO 4217 code
     * @param companyId  tenant company
     * @param asOf       effective date
     * @return {@link ConvertedAmount} with baseAmount, rate, rateAt
     * @throws FxRateNotFoundException if fromCode != toCode and no rate resolves
     */
    ConvertedAmount convert(BigDecimal faceAmount, String fromCode, String toCode,
                            Long companyId, LocalDate asOf);
}
