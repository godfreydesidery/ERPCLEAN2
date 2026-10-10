package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/** ARC-21: a credit-limit refusal names the limit, the exposure and the shortfall. */
class CreditExposureBreachSentenceTest {

    @Test
    void namesLimitExposureAndShortfallInBaseCurrency() {
        var a = new CreditExposureCalculator.Assessment(new BigDecimal("1150000"),
                new BigDecimal("1000000"), "TZS", List.of(), true);
        assertThat(CreditExposureCalculator.breachSentence(a))
                .contains("credit limit is TZS 1,000,000.00")
                .contains("would owe TZS 1,150,000.00")
                .contains("TZS 150,000.00 over the limit")
                .contains("payment of at least TZS 150,000.00");
    }

    @Test
    void withoutAConvertibleLimitFallsBackToTheFigureFreeSentence() {
        var a = new CreditExposureCalculator.Assessment(new BigDecimal("10"), null, "TZS",
                List.of("USD"), true);
        assertThat(CreditExposureCalculator.breachSentence(a))
                .startsWith("This customer's credit limit has been reached.");
    }
}
