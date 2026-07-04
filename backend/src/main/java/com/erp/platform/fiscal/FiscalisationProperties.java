package com.erp.platform.fiscal;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fiscalisation provider selection (ADR-0049 §2), bound from {@code erp.fiscal.*}.
 *
 * <ul>
 *   <li>{@code none} (default, absent config resolves here) — {@link NotConfiguredFiscalisationProvider}.
 *   <li>{@code simulated} — {@link SimulatedFiscalisationProvider}; dev/QA only, fails fast under the
 *       {@code prod} profile ({@link FiscalisationConfig}).
 *   <li>{@code tra} (future) — the real TRA adapter, a drop-in under {@code com.erp.platform.fiscal.tra}.
 * </ul>
 */
@ConfigurationProperties(prefix = "erp.fiscal")
public record FiscalisationProperties(String provider) {

    /** Normalised provider key: lower-cased, trimmed, {@code "none"} when unset/blank. */
    public String effectiveProvider() {
        return (provider != null && !provider.isBlank()) ? provider.strip().toLowerCase() : "none";
    }
}
