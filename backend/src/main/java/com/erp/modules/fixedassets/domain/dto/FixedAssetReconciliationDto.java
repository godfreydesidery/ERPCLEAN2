package com.erp.modules.fixedassets.domain.dto;

import java.math.BigDecimal;

/**
 * FA-to-GL reconciliation bar (ADR-0030 D-9).
 * Both cost and accumulated-depreciation bars compare the register sum to the GL account balance.
 */
public record FixedAssetReconciliationDto(
        /** Register: Σ(carrying_cost WHERE status=IN_SERVICE). */
        BigDecimal registerCostSum,
        /** GL: debit-normal balance of the Fixed Assets account. */
        BigDecimal glCostBalance,
        /** True if they tie (within rounding tolerance). */
        boolean costTies,
        /** Register: Σ(accumulated_depreciation WHERE status=IN_SERVICE). */
        BigDecimal registerAccumDepSum,
        /** GL: credit-normal contra-asset balance (negated for positive presentation). */
        BigDecimal glAccumDepBalance,
        /** True if they tie. */
        boolean accumDepTies
) {}
