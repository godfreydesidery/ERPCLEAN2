package com.erp.platform.common.money;

/**
 * How many decimal places a currency's amounts are rounded to (ISO 4217 "minor units", with the
 * house rule that TZS has none — see V61's {@code currencies} seed).
 *
 * <p>Document arithmetic (line net, VAT, totals) must round in the DOCUMENT's own currency: a USD
 * invoice carries cents, a TZS invoice does not. Rounding a USD invoice to TZS's 0 places turned an
 * 18% VAT of USD 2.16 into USD 2.00. The authoritative values live in the {@code currencies} master
 * ({@link CurrencyMinorUnitsImpl}); {@link #fallback(String)} is used only for a code the master
 * does not list and by unit tests that have no database.
 */
public interface CurrencyMinorUnits {

    /**
     * @param currencyCode ISO 4217 alpha-3 code; null means "unknown" and yields the legacy 0
     * @return decimal places to round amounts in that currency to (0, 2 or 3)
     */
    int of(String currencyCode);

    /** Null-safe overload for the typed currency code carried on document headers. */
    default int of(CurrencyCode currencyCode) {
        return of(currencyCode != null ? currencyCode.value() : null);
    }

    /** Static table used when the master is unavailable or does not list the code. */
    CurrencyMinorUnits FALLBACK = CurrencyMinorUnits::fallback;

    /**
     * The minor units this codebase assumes when the master cannot be consulted. A null code keeps
     * the pre-multi-currency behaviour (whole units) so nothing that never knew its currency changes.
     */
    static int fallback(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return 0;
        }
        return switch (currencyCode.strip().toUpperCase()) {
            case "TZS", "UGX", "RWF", "BIF", "JPY", "KRW" -> 0;
            case "BHD", "KWD", "OMR", "JOD", "TND" -> 3;
            default -> 2;
        };
    }
}
