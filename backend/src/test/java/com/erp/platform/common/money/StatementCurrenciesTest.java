package com.erp.platform.common.money;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class StatementCurrenciesTest {

    @Test
    void oneCurrencyTraded_isTheOnlySection_evenWhenItIsNotTheBase() {
        assertThat(StatementCurrencies.order(List.of("USD", "USD"), null, "TZS"))
                .containsExactly("USD");
    }

    @Test
    void severalCurrencies_partyDefaultFirst_thenBase_thenTheRestAlphabetically() {
        assertThat(StatementCurrencies.order(List.of("KES", "TZS", "EUR", "USD"), "usd", "TZS"))
                .containsExactly("USD", "TZS", "EUR", "KES");
        assertThat(StatementCurrencies.order(List.of("USD", "TZS"), null, "TZS"))
                .containsExactly("TZS", "USD");
    }

    @Test
    void aPartyDefaultTheyNeverTradedIn_doesNotAddAnEmptySection() {
        assertThat(StatementCurrencies.order(List.of("TZS"), "USD", "TZS")).containsExactly("TZS");
    }

    @Test
    void noMovements_theirOwnCurrency_elseBase() {
        assertThat(StatementCurrencies.order(List.of(), "USD", "TZS")).containsExactly("USD");
        assertThat(StatementCurrencies.order(null, " ", "TZS")).containsExactly("TZS");
    }
}
