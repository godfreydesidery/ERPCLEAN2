package com.erp.modules.stock.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.stock.domain.dto.StockValuationReconDto.Status;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * The reconciliation bar must never say two things at once.
 *
 * <p>Live UAT: the period-end report printed "Stock ledger total 1,186,500.00 / GL 1300 balance
 * 1,186,500.00 / Difference 0.00" and immediately beneath it "Reconciled to GL: NO". The figures
 * behind it were {@code computed 1186500.0045} vs {@code expected 1186500.0000} — a 0.0045 residue
 * that the page rounds away but that decided the verdict.
 *
 * <p>The rule these tests pin down: the verdict is decided on the SAME number the page prints
 * (the difference at the posting scale), while the unrounded difference is still carried and named
 * so nothing is quietly dropped. A gap of a postable size still fails.
 */
class StockValuationReconDtoTest {

    private static final String LABEL = "Inventory valuation vs GL 1300 Inventory balance";

    // -------------------------------------------------------------------------
    // The contradiction itself
    // -------------------------------------------------------------------------

    @Test
    void theUatCase_printedFiguresAndVerdictNowAgree() {
        StockValuationReconDto recon = StockValuationReconDto.of(
                LABEL, new BigDecimal("1186500.0045"), new BigDecimal("1186500.0000"));

        assertThat(recon.ties()).isTrue();
        assertThat(recon.status()).isEqualTo(Status.TIED);
        // The number the page prints is the number the verdict was taken on.
        assertThat(recon.difference()).isEqualByComparingTo("0.00");
        assertThat(recon.difference().scale()).isEqualTo(StockValuationReconDto.POSTING_SCALE);
    }

    /**
     * The invariant, stated once: a printed difference of zero and a "not reconciled" verdict can no
     * longer coexist, whichever way the numbers fall.
     */
    @Test
    void aZeroPrintedDifferenceAlwaysMeansReconciled_andViceVersa() {
        String[][] pairs = {
                {"1186500.0045", "1186500.0000"},   // the UAT residue
                {"1186500.0000", "1186500.0045"},   // …and its mirror image
                {"0.0049", "0"},                    // just under half a cent
                {"0.005", "0"},                     // exactly half a cent — rounds up, trips
                {"100.01", "100.00"},               // one clean cent
                {"0", "0"},
                {"0", "12345.67"},                  // stock empty, GL not
                {"98765.4321", "12.00"},
        };

        for (String[] pair : pairs) {
            StockValuationReconDto recon = StockValuationReconDto.of(
                    LABEL, new BigDecimal(pair[0]), new BigDecimal(pair[1]));

            assertThat(recon.difference().signum() == 0)
                    .as("printed difference %s vs ties=%s", recon.difference(), recon.ties())
                    .isEqualTo(recon.ties());
            assertThat(recon.ties()).isEqualTo(recon.status() == Status.TIED);
        }
    }

    // -------------------------------------------------------------------------
    // …without making the check lazier
    // -------------------------------------------------------------------------

    @Test
    void aPostableGapStillFails_evenWhenItCarriesTheSameResidue() {
        StockValuationReconDto recon = StockValuationReconDto.of(
                LABEL, new BigDecimal("1186500.0045"), new BigDecimal("1186499.9945"));

        assertThat(recon.ties()).isFalse();
        assertThat(recon.status()).isEqualTo(Status.OUT_OF_BALANCE);
        assertThat(recon.difference()).isEqualByComparingTo("0.01");
    }

    @Test
    void oneCentIsEnoughToRaiseTheAlarm() {
        StockValuationReconDto recon = StockValuationReconDto.of(
                LABEL, new BigDecimal("100.01"), new BigDecimal("100.00"));

        assertThat(recon.ties()).isFalse();
        assertThat(recon.difference()).isEqualByComparingTo("0.01");
    }

    /**
     * Half a cent rounds up, so accumulating residue does not hide indefinitely: the moment drift
     * reaches a postable size the alarm fires.
     */
    @Test
    void driftTripsTheAlarmAsSoonAsItReachesHalfACent() {
        assertThat(StockValuationReconDto.of(LABEL, new BigDecimal("0.0049"), BigDecimal.ZERO).ties())
                .isTrue();
        assertThat(StockValuationReconDto.of(LABEL, new BigDecimal("0.0050"), BigDecimal.ZERO).ties())
                .isFalse();
    }

    @Test
    void aShortfallIsCaughtJustLikeAnExcess() {
        StockValuationReconDto recon = StockValuationReconDto.of(
                LABEL, new BigDecimal("900.00"), new BigDecimal("1000.00"));

        assertThat(recon.ties()).isFalse();
        assertThat(recon.difference()).isEqualByComparingTo("-100.00");
        assertThat(recon.message()).contains("100.00");
    }

    // -------------------------------------------------------------------------
    // The residue is carried, not swallowed
    // -------------------------------------------------------------------------

    @Test
    void theUnroundedDifferenceIsStillCarried_andNamedInTheMessage() {
        StockValuationReconDto recon = StockValuationReconDto.of(
                LABEL, new BigDecimal("1186500.0045"), new BigDecimal("1186500.0000"));

        assertThat(recon.exactDifference()).isEqualByComparingTo("0.0045");
        assertThat(recon.computed()).isEqualByComparingTo("1186500.0045");  // ledger total, exact
        assertThat(recon.message()).contains("0.0045");
        assertThat(recon.message()).contains("no action is needed");
    }

    @Test
    void anExactTieSaysSoWithoutMentioningRounding() {
        StockValuationReconDto recon = StockValuationReconDto.of(
                LABEL, new BigDecimal("1186500.0000"), new BigDecimal("1186500.0000"));

        assertThat(recon.exactDifference()).isEqualByComparingTo("0");
        assertThat(recon.message()).doesNotContain("rounding");
    }

    // -------------------------------------------------------------------------
    // "Could not be checked" is not "out of balance"
    // -------------------------------------------------------------------------

    @Test
    void anUnmappedInventoryAccountIsItsOwnState_notAGapTheSizeOfTheWholeStock() {
        StockValuationReconDto recon =
                StockValuationReconDto.glAccountNotMapped(LABEL, new BigDecimal("1186500.0045"));

        assertThat(recon.status()).isEqualTo(Status.GL_ACCOUNT_NOT_CONFIGURED);
        assertThat(recon.ties()).isFalse();
        // The alarm must not be able to quote a difference it never measured.
        assertThat(recon.expected()).isNull();
        assertThat(recon.difference()).isNull();
        assertThat(recon.exactDifference()).isNull();
        assertThat(recon.computed()).isEqualByComparingTo("1186500.0045");
    }

    @Test
    void anUnusableInventoryAccountIsTheSameState_withItsOwnInstruction() {
        StockValuationReconDto recon =
                StockValuationReconDto.glAccountNotUsable(LABEL, new BigDecimal("500.00"));

        assertThat(recon.status()).isEqualTo(Status.GL_ACCOUNT_NOT_CONFIGURED);
        assertThat(recon.expected()).isNull();
        assertThat(recon.message()).contains("no longer in use");
        assertThat(recon.message()).contains("General Ledger settings");
    }

    @Test
    void everyMessageSaysWhatToDoNext_andLeaksNoInternalDetail() {
        String[] messages = {
                StockValuationReconDto.of(LABEL, new BigDecimal("1"), BigDecimal.ZERO).message(),
                StockValuationReconDto.of(LABEL, BigDecimal.ZERO, BigDecimal.ZERO).message(),
                StockValuationReconDto.glAccountNotMapped(LABEL, BigDecimal.TEN).message(),
                StockValuationReconDto.glAccountNotUsable(LABEL, BigDecimal.TEN).message(),
        };

        for (String message : messages) {
            assertThat(message).isNotBlank();
            assertThat(message).doesNotContain("gl_configs")
                    .doesNotContain("chart_of_accounts")
                    .doesNotContain("on_hand_value")
                    .doesNotContain("Exception")
                    .doesNotContain("null")
                    .doesNotContain("INVENTORY.VALUATION")
                    .doesNotContain("403")
                    .doesNotContain("500");
        }
    }

    // -------------------------------------------------------------------------

    @Test
    void missingFiguresAreTreatedAsZero_notAsAFailedCheck() {
        StockValuationReconDto recon = StockValuationReconDto.of(LABEL, null, null);

        assertThat(recon.computed()).isEqualByComparingTo("0");
        assertThat(recon.expected()).isEqualByComparingTo("0");
        assertThat(recon.ties()).isTrue();
        assertThat(recon.status()).isEqualTo(Status.TIED);
    }
}
