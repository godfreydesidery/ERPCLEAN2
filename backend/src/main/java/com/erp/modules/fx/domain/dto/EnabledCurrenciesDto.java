package com.erp.modules.fx.domain.dto;

import java.util.List;

/**
 * Response for {@code GET /currencies/enabled} (ADR-0039 D-8).
 *
 * <p>Returns the effective enabled list for the active company/branch scope, plus the
 * resolved default document currency (branch default → company default → company base).
 */
public record EnabledCurrenciesDto(
        /** The resolved default document currency code for this scope. */
        String        resolvedDefault,
        /** The allowed currency codes in alphabetical order. */
        List<String>  enabled) {
}
