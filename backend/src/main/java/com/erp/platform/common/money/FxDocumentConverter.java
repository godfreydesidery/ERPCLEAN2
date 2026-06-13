package com.erp.platform.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Single conversion chokepoint for document-level foreign→base conversion (ADR-0036 D-3).
 *
 * <p>All posting code routes through here instead of hand-rolling conversion, so the
 * balancing-leg-as-plug rule is implemented and tested <em>once</em>. The unchanged
 * {@link com.erp.modules.gl.service.GLPostingService#post post()} Σ-check is the
 * defence-in-depth backstop.
 *
 * <h3>Rounding residual — the balancing-leg-as-plug rule (D-3):</h3>
 * When a multi-leg foreign document is converted to base, per-leg HALF_UP rounding may leave
 * a 1-minor-unit residual. The caller designates one leg as the "balancing leg" (the AR/AP
 * control leg); that leg is computed as the exact arithmetic complement of all other legs so
 * the entry balances exactly and the unchanged Σ-check passes.
 *
 * <h3>Identity short-circuit (D-1/D-8):</h3>
 * When {@code docCurrency.equals(baseCurrency)}, conversion returns face amounts unchanged
 * at rate=1. No rate-table lookup, no rounding, byte-identical to the pre-FX path.
 */
@Component
public class FxDocumentConverter {

    private final CurrencyConversionService conversionService;

    public FxDocumentConverter(CurrencyConversionService conversionService) {
        this.conversionService = conversionService;
    }

    /**
     * Convert a face amount to base, HALF_UP to base minor units.
     *
     * <p>The identity short-circuit ({@code docCurrency == baseCurrency}) is handled by
     * {@link CurrencyConversionService#toBase toBase} — this method is a thin pass-through
     * that keeps call-site code readable.
     *
     * @param faceAmount  face value in {@code docCurrency}
     * @param docCurrency ISO 4217 document currency
     * @param companyId   tenant (determines base currency and rate lookup)
     * @param asOf        posting / effective date
     * @return {@link ConvertedAmount} with baseAmount, rate, rateAt
     * @throws FxRateNotFoundException if docCurrency != base and no rate resolves
     */
    public ConvertedAmount toBase(BigDecimal faceAmount, String docCurrency,
                                  Long companyId, LocalDate asOf) {
        return conversionService.toBase(faceAmount, docCurrency, companyId, asOf);
    }

    /**
     * Compute the balancing plug: the exact base amount needed so that all legs sum to zero.
     *
     * <p>Given a list of independently-rounded base legs (debits positive, credits negative),
     * the plug is their negated sum. This absorbs any HALF_UP rounding residual into the
     * designated control leg (AR/AP/Cash), keeping Σbase == 0 exactly (D-3).
     *
     * @param convertedLegs base amounts of the OTHER legs (debits +, credits -)
     * @param scale         minor-unit scale for the result (base currency minor units)
     * @return exact plug amount so that Σ(convertedLegs) + plug == 0
     */
    public BigDecimal balancingPlug(List<BigDecimal> convertedLegs, int scale) {
        BigDecimal sum = convertedLegs.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        // plug = -sum, rounded to the same scale (sum is already at scale so setScale is cosmetic)
        return sum.negate().setScale(scale, RoundingMode.HALF_UP);
    }
}
