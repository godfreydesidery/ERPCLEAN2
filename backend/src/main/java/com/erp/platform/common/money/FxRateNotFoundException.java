package com.erp.platform.common.money;

/**
 * Thrown when {@code CurrencyConversionService} cannot resolve a rate for a foreign-currency
 * conversion (ADR-0036 D-1, rule 3: loud failure — a missing foreign rate is NEVER silently
 * substituted with par=1).
 *
 * <p>The {@code from==base} identity short-circuit is the ONLY rate=1 path; this exception
 * is thrown in every other case where no active rate row resolves for the requested
 * (companyId, fromCurrency, toCurrency, asOfDate) tuple.
 */
public class FxRateNotFoundException extends RuntimeException {

    private final Long   companyId;
    private final String fromCurrency;
    private final String toCurrency;
    private final Object asOf;

    public FxRateNotFoundException(Long companyId, String fromCurrency, String toCurrency, Object asOf) {
        super(String.format(
                "No active exchange rate found for company %d: %s → %s as of %s. "
              + "Add a rate via the currency-rate maintenance screen before processing this document.",
                companyId, fromCurrency, toCurrency, asOf));
        this.companyId    = companyId;
        this.fromCurrency = fromCurrency;
        this.toCurrency   = toCurrency;
        this.asOf         = asOf;
    }

    public Long   getCompanyId()    { return companyId; }
    public String getFromCurrency() { return fromCurrency; }
    public String getToCurrency()   { return toCurrency; }
    public Object getAsOf()         { return asOf; }
}
