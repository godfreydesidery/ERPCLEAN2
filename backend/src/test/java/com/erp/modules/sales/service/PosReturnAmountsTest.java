package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.sales.domain.dto.PosReturnedLineTotalsDto;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** ADR-0072 pro-rating: proportional, drift-free, and the last unit takes the exact remainder. */
class PosReturnAmountsTest {

    private static BigDecimal d(String v) {
        return new BigDecimal(v);
    }

    /** Accumulates what earlier returns of one line gave back, as the repository sum would. */
    private static PosReturnedLineTotalsDto after(PosReturnedLineTotalsDto prior,
                                                  PosReturnAmounts.Slice s) {
        BigDecimal q  = (prior == null ? BigDecimal.ZERO : prior.qtyReturned()).add(s.qty());
        BigDecimal qb = (prior == null ? BigDecimal.ZERO : prior.qtyReturnedBase()).add(s.qtyBase());
        BigDecimal n  = (prior == null ? BigDecimal.ZERO : prior.netAmount()).add(s.net());
        BigDecimal ds = (prior == null ? BigDecimal.ZERO : prior.discountAmount()).add(s.discount());
        BigDecimal v  = (prior == null ? BigDecimal.ZERO : prior.vatAmount()).add(s.vat());
        return new PosReturnedLineTotalsDto(1L, q, qb, n, ds, v, n.add(v));
    }

    @Test
    void halfOfALine_givesBackHalfOfNetVatAndGross() {
        var s = PosReturnAmounts.slice(d("4"), d("4"), d("1000"), null, d("180"), d("2"), null, 0);

        assertThat(s.net()).isEqualByComparingTo("500");
        assertThat(s.vat()).isEqualByComparingTo("90");
        assertThat(s.gross()).isEqualByComparingTo("590");
        assertThat(s.qtyBase()).isEqualByComparingTo("2");
        assertThat(s.discount()).isEqualByComparingTo("0");
    }

    @Test
    void theFinalReturnOfALine_takesTheExactRemainder_soThirdsNeverDrift() {
        // 3 units sold for net 1000 / vat 180: a third is 333.33 - three returns must sum back to
        // exactly 1000 and 180, whatever the rounding in between.
        BigDecimal soldQty = d("3");
        PosReturnedLineTotalsDto prior = null;
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal vat = BigDecimal.ZERO;
        for (int i = 0; i < 3; i++) {
            var s = PosReturnAmounts.slice(soldQty, soldQty, d("1000"), d("30"), d("180"),
                    BigDecimal.ONE, prior, 0);
            net = net.add(s.net());
            vat = vat.add(s.vat());
            prior = after(prior, s);
        }
        assertThat(net).as("net returned across the three returns").isEqualByComparingTo("1000");
        assertThat(vat).as("VAT returned across the three returns").isEqualByComparingTo("180");
        assertThat(prior.discountAmount()).isEqualByComparingTo("30");
        assertThat(prior.qtyReturnedBase()).isEqualByComparingTo("3");
    }

    @Test
    void remainderIsExactEvenAfterAnUnevenFirstReturn() {
        // 7 units, net 1000: return 2, then 4, then the last 1 (the remainder).
        BigDecimal soldQty = d("7");
        var first  = PosReturnAmounts.slice(soldQty, soldQty, d("1000"), null, d("0"), d("2"), null, 0);
        var second = PosReturnAmounts.slice(soldQty, soldQty, d("1000"), null, d("0"), d("4"),
                after(null, first), 0);
        var third  = PosReturnAmounts.slice(soldQty, soldQty, d("1000"), null, d("0"), d("1"),
                after(after(null, first), second), 0);

        assertThat(first.net().add(second.net()).add(third.net())).isEqualByComparingTo("1000");
        assertThat(third.net()).as("last unit takes the remainder, not 1/7 again")
                .isEqualByComparingTo(d("1000").subtract(first.net()).subtract(second.net()));
    }

    @Test
    void grossIsAlwaysNetPlusVat_includingForAnInclusivePricedLine() {
        // Gross 1180 inclusive: net 1000 + vat 180. Half: gross must be exactly net + vat.
        var s = PosReturnAmounts.slice(d("2"), d("2"), d("1000"), null, d("180"), d("1"), null, 0);
        assertThat(s.gross()).isEqualByComparingTo(s.net().add(s.vat()));
        assertThat(s.gross()).isEqualByComparingTo("590");
    }

    @Test
    void currencyWithCents_roundsToTheCurrencysMinorUnit() {
        // USD: net 10.00 / vat 1.80 over 3 units - a third is 3.33 / 0.60.
        var s = PosReturnAmounts.slice(d("3"), d("3"), d("10.00"), null, d("1.80"), d("1"), null, 2);
        assertThat(s.net()).isEqualByComparingTo("3.33");
        assertThat(s.vat()).isEqualByComparingTo("0.60");
    }

    @Test
    void unitConversion_baseQuantityFollowsTheSoldLinesRatio() {
        // 2 cartons sold = 48 base units; returning 1 carton gives back 24 base units.
        var s = PosReturnAmounts.slice(d("2"), d("48"), d("2000"), null, d("0"), d("1"), null, 0);
        assertThat(s.qtyBase()).isEqualByComparingTo("24");
        assertThat(s.qty()).isEqualByComparingTo("1");
    }

    @Test
    void returningMoreThanWasSold_isRefused() {
        var prior = new PosReturnedLineTotalsDto(1L, d("2"), d("2"), d("500"), d("0"), d("90"),
                d("590"));
        assertThatThrownBy(() -> PosReturnAmounts.slice(d("3"), d("3"), d("750"), null, d("135"),
                d("2"), prior, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
