package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** ARC-18: only the tenders the ar_receipts CHECK admits get through, as a friendly 400. */
class ArReceiptTenderTest {

    @Test
    void everyCheckValue_isAccepted_caseInsensitive() {
        assertThat(ArReceiptServiceImpl.normaliseTender("cash")).isEqualTo("CASH");
        assertThat(ArReceiptServiceImpl.normaliseTender(" MOBILE_MONEY ")).isEqualTo("MOBILE_MONEY");
        assertThat(ArReceiptServiceImpl.normaliseTender("CARD")).isEqualTo("CARD");
        assertThat(ArReceiptServiceImpl.normaliseTender("BANK_TRANSFER")).isEqualTo("BANK_TRANSFER");
        assertThat(ArReceiptServiceImpl.normaliseTender("CHEQUE")).isEqualTo("CHEQUE");
    }

    @Test
    void other_isAFriendlyError_notAConstraintViolation() {
        assertThatThrownBy(() -> ArReceiptServiceImpl.normaliseTender("OTHER"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Choose how the customer paid: Cash, Bank transfer, Mobile money, Cheque or Card.");
        assertThatThrownBy(() -> ArReceiptServiceImpl.normaliseTender(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
