package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/** The friendly refusals behind live-test defects 3, 5 and 6 (AP payment / bill entry rules). */
class ApPaymentRulesTest {

    @Test
    void aBillThatExistsButIsNotPayable_saysWhy() {
        assertThat(ApPaymentServiceImpl.notPayableMessage(SupplierBillStatus.HELD))
                .isEqualTo("This bill is on hold and can't be paid until it is released.");
        assertThat(ApPaymentServiceImpl.notPayableMessage(SupplierBillStatus.DRAFT))
                .contains("hasn't been matched or approved");
        assertThat(ApPaymentServiceImpl.notPayableMessage(SupplierBillStatus.PAID))
                .contains("already been paid");
    }

    @Test
    void wht_mustHaveAType_andBeLessThanThePayment() {
        BigDecimal paid = new BigDecimal("400000");
        assertThatCode(() -> ApPaymentServiceImpl.assertWhtFits(null, null, paid)).doesNotThrowAnyException();
        assertThatCode(() -> ApPaymentServiceImpl.assertWhtFits(null, BigDecimal.ZERO, paid))
                .doesNotThrowAnyException();
        assertThatCode(() -> ApPaymentServiceImpl.assertWhtFits("T", new BigDecimal("20000"), paid))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> ApPaymentServiceImpl.assertWhtFits(" ", new BigDecimal("20000"), paid))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApPaymentServiceImpl.assertWhtFits("T", paid, paid))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ApPaymentServiceImpl.assertWhtFits("T", new BigDecimal("-1"), paid))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aBillLineVatRate_isAFractionBelowOne() {
        assertThatCode(() -> SupplierBillServiceImpl.assertVatRate(null, 1)).doesNotThrowAnyException();
        assertThatCode(() -> SupplierBillServiceImpl.assertVatRate(BigDecimal.ZERO, 1)).doesNotThrowAnyException();
        assertThatCode(() -> SupplierBillServiceImpl.assertVatRate(new BigDecimal("0.18"), 1))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> SupplierBillServiceImpl.assertVatRate(new BigDecimal("18"), 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith("Line 2: the VAT rate must be a fraction between 0 and 1");
        assertThatThrownBy(() -> SupplierBillServiceImpl.assertVatRate(BigDecimal.ONE, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> SupplierBillServiceImpl.assertVatRate(new BigDecimal("-0.01"), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
