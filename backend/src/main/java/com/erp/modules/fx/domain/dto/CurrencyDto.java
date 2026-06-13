package com.erp.modules.fx.domain.dto;

/**
 * Wire shape for a currency master row (ADR-0036 D-7, ADR-0005 D-7).
 * Both {@code id} and {@code uid} are included per PROJECT-CONVENTIONS identity pattern.
 * Long ids serialise as JSON strings via the global Jackson config.
 */
public record CurrencyDto(
        Long   id,
        String uid,
        String code,
        String name,
        String symbol,
        short  minorUnits,
        boolean active,
        String status
) {}
