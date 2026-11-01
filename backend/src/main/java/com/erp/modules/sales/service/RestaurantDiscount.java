package com.erp.modules.sales.service;

import com.erp.modules.sales.service.RestaurantBillCalculator.PricedLine;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

/** An order discount (percent or amount off the VAT-inclusive total), spread as line discounts. */
final class RestaurantDiscount {

    private static final int SCALE = 2;

    private RestaurantDiscount() {
    }

    /** The VAT-inclusive amount to take off: a percent of {@code gross}, or an amount. */
    static BigDecimal target(BigDecimal gross, BigDecimal percent, BigDecimal amount) {
        boolean byPercent = percent != null && percent.signum() > 0;
        boolean byAmount = amount != null && amount.signum() > 0;
        if (byPercent && byAmount) {
            throw new IllegalArgumentException(
                    "Give the discount as a percentage or as an amount, not both.");
        }
        if (byPercent) {
            if (percent.compareTo(BigDecimal.valueOf(100)) > 0) {
                throw new IllegalArgumentException("A discount cannot be more than 100%.");
            }
            return gross.multiply(percent)
                    .divide(BigDecimal.valueOf(100), SCALE, RoundingMode.HALF_UP);
        }
        if (byAmount) {
            if (amount.compareTo(gross) > 0) {
                throw new IllegalArgumentException("The discount is larger than the bill.");
            }
            return amount.setScale(SCALE, RoundingMode.HALF_UP);
        }
        return BigDecimal.ZERO;
    }

    /** Per-line discounts (line basis) taking {@code target} off; the last line takes rounding. */
    static List<BigDecimal> spread(List<PricedLine> lines, BigDecimal gross, BigDecimal target) {
        List<BigDecimal> out = new ArrayList<>(lines.size());
        if (target.signum() <= 0 || gross.signum() <= 0) {
            lines.forEach(l -> out.add(BigDecimal.ZERO));
            return out;
        }
        int last = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (lines.get(i).gross().signum() > 0) {
                last = i;
            }
        }
        BigDecimal given = BigDecimal.ZERO;
        for (int i = 0; i < lines.size(); i++) {
            PricedLine l = lines.get(i);
            if (l.gross().signum() <= 0) {
                out.add(BigDecimal.ZERO);
                continue;
            }
            BigDecimal share = i == last
                    ? target.subtract(given)
                    : target.multiply(l.gross()).divide(gross, SCALE, RoundingMode.HALF_UP);
            given = given.add(share);
            BigDecimal basis = share.multiply(l.raw())
                    .divide(l.gross(), SCALE, RoundingMode.HALF_UP);
            out.add(basis.min(l.raw()).max(BigDecimal.ZERO));
        }
        return out;
    }
}
