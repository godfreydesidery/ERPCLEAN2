package com.erp.modules.iam.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class PosPinPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {"2580", "1357", "0418", "9102", "1123"})
    void ordinaryPinsAreAccepted(String pin) {
        assertThatCode(() -> PosPinPolicy.validate(pin)).doesNotThrowAnyException();
    }

    @ParameterizedTest
    @ValueSource(strings = {"0000", "7777", "1234", "0123", "6789", "4321", "9876", "3210"})
    void easyPinsAreRefused(String pin) {
        assertThatThrownBy(() -> PosPinPolicy.validate(pin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("too easy");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "123", "12345", "12a4", " 123", "١٢٣٤"})
    void anythingButFourDigitsIsRefused(String pin) {
        assertThatThrownBy(() -> PosPinPolicy.validate(pin))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly 4 digits");
    }

    @Test
    void noPinIsRefused() {
        assertThatThrownBy(() -> PosPinPolicy.validate(null)).isInstanceOf(IllegalArgumentException.class);
    }
}
