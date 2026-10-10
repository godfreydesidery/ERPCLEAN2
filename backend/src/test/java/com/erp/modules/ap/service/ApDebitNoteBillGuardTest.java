package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.platform.common.api.ConflictException;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/** AP-14: a debit note may only reduce the same supplier's bills, in the same currency. */
class ApDebitNoteBillGuardTest {

    private static SupplierBill bill(Long supplierId, String currency) {
        return new SupplierBill(10L, 20L, supplierId, "INV-1", SupplierBillSource.BILL, null,
                LocalDate.now(), LocalDate.now().plusDays(30),
                new BigDecimal("100"), BigDecimal.ZERO, new BigDecimal("100"), currency, 1L);
    }

    @Test
    void sameSupplierSameCurrency_passes() {
        assertThatCode(() -> ApDebitNoteServiceImpl.assertSameSupplierAndCurrency(
                5L, "TZS", bill(5L, "TZS"))).doesNotThrowAnyException();
    }

    @Test
    void otherSuppliersBill_isRefused() {
        assertThatThrownBy(() -> ApDebitNoteServiceImpl.assertSameSupplierAndCurrency(
                5L, "TZS", bill(6L, "TZS")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different supplier");
    }

    @Test
    void otherCurrencyBill_isRefused() {
        assertThatThrownBy(() -> ApDebitNoteServiceImpl.assertSameSupplierAndCurrency(
                5L, "TZS", bill(5L, "USD")))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("different currency");
    }
}
