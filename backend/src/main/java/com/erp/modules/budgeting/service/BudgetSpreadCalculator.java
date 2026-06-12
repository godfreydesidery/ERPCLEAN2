package com.erp.modules.budgeting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Spreads an annual budget amount evenly across N periods (ADR-0034 D-4 / FR-BUD-08b).
 *
 * <p>Algorithm: divide {@code annual} by {@code periods} (HALF_UP, 4 decimal places).
 * Compute the sum of (periods - 1) equal instalments; the last period absorbs the remainder
 * to ensure: Σ spread = annual exactly (BR-BUD-09 / ADR-0005 HALF_UP).
 */
@Component
public class BudgetSpreadCalculator {

    private static final int SCALE = 4;

    /**
     * Spread {@code annual} evenly across {@code periods} periods (HALF_UP).
     * Returns a list of exactly {@code periods} non-negative amounts.
     *
     * @param annual  the annual budget amount (must be >= 0)
     * @param periods number of periods (typically 12)
     * @return per-period amounts, last absorbs the rounding remainder
     */
    public List<BigDecimal> spread(BigDecimal annual, int periods) {
        if (annual.compareTo(BigDecimal.ZERO) < 0) {
            throw new IllegalArgumentException("Annual amount must be >= 0");
        }
        if (periods <= 0) {
            throw new IllegalArgumentException("periods must be > 0");
        }

        BigDecimal perPeriod = annual.divide(
                BigDecimal.valueOf(periods), SCALE, RoundingMode.HALF_UP);

        List<BigDecimal> result = new ArrayList<>(periods);
        BigDecimal allocated = BigDecimal.ZERO;
        for (int i = 0; i < periods - 1; i++) {
            result.add(perPeriod);
            allocated = allocated.add(perPeriod);
        }
        // Last period: annual - sum of the first (periods-1) instalments
        BigDecimal remainder = annual.subtract(allocated);
        // Ensure remainder >= 0 (possible rounding edge on very small amounts)
        if (remainder.compareTo(BigDecimal.ZERO) < 0) {
            remainder = BigDecimal.ZERO.setScale(SCALE, RoundingMode.HALF_UP);
        }
        result.add(remainder);
        return result;
    }
}
