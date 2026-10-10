package com.erp.modules.tax.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.tax.domain.entity.VatAdjustment;
import com.erp.modules.tax.domain.enums.VatAdjustmentReason;
import com.erp.modules.tax.domain.enums.VatAdjustmentSign;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** ACC-06: where each VAT adjustment's counter leg posts on filing. */
class VatReturnFilingPosterTest {

    private static VatAdjustment adj(VatAdjustmentReason reason, VatAdjustmentSign sign) {
        return new VatAdjustment(1L, 1L, reason, sign, BigDecimal.TEN, null, null);
    }

    @Test
    void outputSideCorrections_postAgainstVatPayable() {
        assertThat(VatReturnFilingPoster.counterKey(
                adj(VatAdjustmentReason.CREDIT_NOTE_VAT, VatAdjustmentSign.DECREASE)))
                .isEqualTo(GlConfigKey.VAT_PAYABLE);
        assertThat(VatReturnFilingPoster.counterKey(
                adj(VatAdjustmentReason.PRIOR_PERIOD_CORRECTION, VatAdjustmentSign.INCREASE)))
                .isEqualTo(GlConfigKey.VAT_PAYABLE);
        assertThat(VatReturnFilingPoster.counterKey(
                adj(VatAdjustmentReason.OTHER, VatAdjustmentSign.INCREASE)))
                .isEqualTo(GlConfigKey.VAT_PAYABLE);
    }

    @Test
    void inputSideCorrections_postAgainstVatInput() {
        assertThat(VatReturnFilingPoster.counterKey(
                adj(VatAdjustmentReason.DEBIT_NOTE_VAT, VatAdjustmentSign.INCREASE)))
                .isEqualTo(GlConfigKey.VAT_INPUT);
        assertThat(VatReturnFilingPoster.counterKey(
                adj(VatAdjustmentReason.PRIOR_PERIOD_CORRECTION, VatAdjustmentSign.DECREASE)))
                .isEqualTo(GlConfigKey.VAT_INPUT);
        assertThat(VatReturnFilingPoster.counterKey(
                adj(VatAdjustmentReason.OTHER, VatAdjustmentSign.DECREASE)))
                .isEqualTo(GlConfigKey.VAT_INPUT);
    }

    @Test
    void badDebtRelief_postsAgainstBadDebtExpense() {
        assertThat(VatReturnFilingPoster.counterKey(
                adj(VatAdjustmentReason.BAD_DEBT_RELIEF, VatAdjustmentSign.DECREASE)))
                .isEqualTo(GlConfigKey.BAD_DEBT_EXPENSE);
    }
}
