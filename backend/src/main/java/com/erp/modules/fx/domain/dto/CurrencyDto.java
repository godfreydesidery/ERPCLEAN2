package com.erp.modules.fx.domain.dto;

/**
 * Wire shape for a currency master row (ADR-0036 D-7, ADR-0005 D-7).
 * Both {@code id} and {@code uid} are included per PROJECT-CONVENTIONS identity pattern.
 * Long ids serialise as JSON strings via the global Jackson config.
 * P2-M1: added numericCode (ISO-4217 numeric).
 */
public record CurrencyDto(
        Long   id,
        String uid,
        String code,
        String name,
        String symbol,
        short  minorUnits,
        /** ISO-4217 numeric code (P2-M1); null if not seeded. */
        String numericCode,
        boolean active,
        String status
) {}
