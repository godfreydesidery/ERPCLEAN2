package com.erp.platform.bootstrap;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fresh-DB bootstrap config (bound from {@code erp.bootstrap.*}). When enabled and the DB has no
 * organisation, the app creates org + company + default branch + root admin from these values.
 *
 * <p>Currency sub-config (ADR-0039 D-9):
 * <ul>
 *   <li>{@code erp.bootstrap.currency.base} — the company ledger base currency (default {@code TZS}).
 *   <li>{@code erp.bootstrap.currency.default} — the default document currency (default {@code TZS}).
 *   <li>{@code erp.bootstrap.currency.enabled} — CSV of codes to enable; defaults to the Classic
 *       EAC-6 + USD + EUR = {@code TZS,KES,UGX,RWF,BIF,SSP,USD,EUR}.
 * </ul>
 */
@ConfigurationProperties(prefix = "erp.bootstrap")
public record BootstrapProperties(
        boolean enabled,
        String organisationName,
        String companyCode,
        String companyName,
        String branchCode,
        String branchName,
        String adminUsername,
        String adminDisplayName,
        String adminPassword,
        String timeZone,
        CurrencyConfig currency) {

    /**
     * Currency policy for the bootstrap company (ADR-0039 D-9).
     *
     * @param base    ledger base currency code (default {@code TZS})
     * @param defaultCurrency default document currency code (default {@code TZS})
     * @param enabled CSV list of currency codes to enable; default Classic EAC-6 + USD + EUR
     */
    public record CurrencyConfig(
            String base,
            String defaultCurrency,
            List<String> enabled) {

        /** Effective base: {@code TZS} when not configured. */
        public String effectiveBase() {
            return (base != null && !base.isBlank()) ? base.strip().toUpperCase() : "TZS";
        }

        /** Effective default document currency: same as base when not configured. */
        public String effectiveDefault() {
            return (defaultCurrency != null && !defaultCurrency.isBlank())
                    ? defaultCurrency.strip().toUpperCase()
                    : effectiveBase();
        }

        /** Effective enabled set: Classic EAC-6 + USD + EUR when not configured. */
        public List<String> effectiveEnabled() {
            if (enabled != null && !enabled.isEmpty()) {
                return enabled.stream().map(s -> s.strip().toUpperCase()).toList();
            }
            return List.of("TZS", "KES", "UGX", "RWF", "BIF", "SSP", "USD", "EUR");
        }
    }

    /** Null-safe access to the currency config; returns a default instance if not bound. */
    public CurrencyConfig effectiveCurrency() {
        return currency != null ? currency : new CurrencyConfig(null, null, null);
    }
}
