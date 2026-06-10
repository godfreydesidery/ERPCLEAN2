package com.erp.modules.reporting.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.reporting.domain.enums.StatementSection;
import org.junit.jupiter.api.Test;

/**
 * Pure-unit tests for {@link StatementClassifier} (ADR-0018 D-4 / D-7).
 *
 * <p>Regression guard for the adversarial-review finding: the VAT/WHT control accounts — notably
 * 1500 (WHT Receivable) and 1400 (VAT Input) — are short-term working capital and MUST present as
 * CURRENT_ASSETS on the Balance Sheet and OPERATING on the Cash-Flow, NOT non-current/investing.
 * The non-current asset band starts at 1600.
 */
class StatementClassifierTest {

    private final StatementClassifier classifier = new StatementClassifier();

    @Test
    void vatWhtControlAssets_areCurrentAndOperating() {
        for (String code : new String[] {"1400", "1500"}) {
            assertThat(classifier.classify(AccountType.ASSET, code))
                    .as("BS section for asset " + code).isEqualTo(StatementSection.CURRENT_ASSETS);
            assertThat(classifier.classifyForCashFlow(AccountType.ASSET, code))
                    .as("CF section for asset " + code).isEqualTo(StatementSection.OPERATING);
        }
    }

    @Test
    void coreCurrentAssets_areCurrentAndOperating() {
        for (String code : new String[] {"1000", "1100", "1200", "1300"}) {
            assertThat(classifier.classify(AccountType.ASSET, code)).isEqualTo(StatementSection.CURRENT_ASSETS);
            assertThat(classifier.classifyForCashFlow(AccountType.ASSET, code)).isEqualTo(StatementSection.OPERATING);
        }
    }

    @Test
    void assetsAt1600AndAbove_areNonCurrentAndInvesting() {
        assertThat(classifier.classify(AccountType.ASSET, "1600")).isEqualTo(StatementSection.NON_CURRENT_ASSETS);
        assertThat(classifier.classify(AccountType.ASSET, "1800")).isEqualTo(StatementSection.NON_CURRENT_ASSETS);
        assertThat(classifier.classifyForCashFlow(AccountType.ASSET, "1700")).isEqualTo(StatementSection.INVESTING);
    }

    @Test
    void liabilities_currentVsNonCurrent_andCashFlow() {
        for (String code : new String[] {"2100", "2200", "2300", "2400"}) {
            assertThat(classifier.classify(AccountType.LIABILITY, code)).isEqualTo(StatementSection.CURRENT_LIABILITIES);
            assertThat(classifier.classifyForCashFlow(AccountType.LIABILITY, code)).isEqualTo(StatementSection.OPERATING);
        }
        assertThat(classifier.classify(AccountType.LIABILITY, "2600")).isEqualTo(StatementSection.NON_CURRENT_LIABILITIES);
        assertThat(classifier.classifyForCashFlow(AccountType.LIABILITY, "2600")).isEqualTo(StatementSection.FINANCING);
    }

    @Test
    void equityIncomeExpense_sections() {
        assertThat(classifier.classify(AccountType.EQUITY, "3000")).isEqualTo(StatementSection.EQUITY);
        assertThat(classifier.classify(AccountType.EQUITY, "3900")).isEqualTo(StatementSection.EQUITY);
        assertThat(classifier.classify(AccountType.INCOME, "4100")).isEqualTo(StatementSection.REVENUE);
        assertThat(classifier.classify(AccountType.EXPENSE, "5100")).isEqualTo(StatementSection.COST_OF_SALES);
        assertThat(classifier.classify(AccountType.EXPENSE, "5150")).isEqualTo(StatementSection.COST_OF_SALES);
        assertThat(classifier.classify(AccountType.EXPENSE, "5200")).isEqualTo(StatementSection.OPERATING_EXPENSES);
    }

    @Test
    void equityAndIncomeExpense_cashFlowSections() {
        assertThat(classifier.classifyForCashFlow(AccountType.EQUITY, "3000")).isEqualTo(StatementSection.FINANCING);
        assertThat(classifier.classifyForCashFlow(AccountType.INCOME, "4100")).isEqualTo(StatementSection.OPERATING);
        assertThat(classifier.classifyForCashFlow(AccountType.EXPENSE, "5100")).isEqualTo(StatementSection.OPERATING);
    }
}
