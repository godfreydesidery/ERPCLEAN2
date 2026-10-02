package com.erp.modules.ap.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.products.domain.enums.VatStatus;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pins supplier-bill line VAT against the two rounding defects found on the sales side: it must
 * not be rounded to whole units (sales used a fixed 0 dp, turning USD 2.16 into 2.00) and must not
 * treat the stored fractional rate as a percentage (the sales return divided 0.18 by 100).
 */
class SupplierBillLineVatTest {

    private static final BigDecimal RATE = new BigDecimal("0.1800");

    @Test
    void usdBillLine_vatKeepsItsCents() {
        BigDecimal vat = SupplierBillServiceImpl.computeLineVat(
                new BigDecimal("12.00"), VatStatus.STANDARD, RATE);

        assertThat(vat).isEqualByComparingTo("2.16");
    }

    @Test
    void rateIsAFraction_notAPercentage() {
        BigDecimal vat = SupplierBillServiceImpl.computeLineVat(
                new BigDecimal("7500"), VatStatus.STANDARD, RATE);

        assertThat(vat).isEqualByComparingTo("1350");
    }

    @Test
    void exemptAndZeroRatedLines_carryNoVat() {
        assertThat(SupplierBillServiceImpl.computeLineVat(
                new BigDecimal("100"), VatStatus.EXEMPT, RATE)).isEqualByComparingTo("0");
        assertThat(SupplierBillServiceImpl.computeLineVat(
                new BigDecimal("100"), VatStatus.ZERO_RATED, RATE)).isEqualByComparingTo("0");
    }
}
