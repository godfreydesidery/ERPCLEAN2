package com.erp.modules.parties.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.parties.domain.entity.PaymentTerms;
import com.erp.modules.parties.domain.enums.PaymentTermsBasis;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link PaymentTermsDueDateCalculator} (ADR-0041 D1).
 * Pure / no Spring context.
 */
class PaymentTermsDueDateCalculatorTest {

    private static PaymentTerms terms(PaymentTermsBasis basis, int netDays) {
        return new PaymentTerms(1L, "NET", "Net terms", basis, netDays, 99L);
    }

    @Test
    void daysAfterInvoice_addsNetDaysToDocDate() {
        PaymentTerms t = terms(PaymentTermsBasis.DAYS_AFTER_INVOICE, 30);
        LocalDate due = PaymentTermsDueDateCalculator.derive(LocalDate.of(2026, 1, 15), t);
        assertThat(due).isEqualTo(LocalDate.of(2026, 2, 14));
    }

    @Test
    void daysAfterMonthEnd_addsNetDaysToEndOfMonth() {
        PaymentTerms t = terms(PaymentTermsBasis.DAYS_AFTER_MONTH_END, 15);
        // Jan 2026 has 31 days → end-of-month is 2026-01-31, +15 = 2026-02-15
        LocalDate due = PaymentTermsDueDateCalculator.derive(LocalDate.of(2026, 1, 10), t);
        assertThat(due).isEqualTo(LocalDate.of(2026, 2, 15));
    }

    @Test
    void dueOnReceipt_ignoresNetDays() {
        PaymentTerms t = terms(PaymentTermsBasis.DUE_ON_RECEIPT, 99);
        LocalDate doc = LocalDate.of(2026, 3, 5);
        assertThat(PaymentTermsDueDateCalculator.derive(doc, t)).isEqualTo(doc);
    }

    @Test
    void nullTerms_fallsBackToDocDate() {
        LocalDate doc = LocalDate.of(2026, 6, 1);
        assertThat(PaymentTermsDueDateCalculator.derive(doc, null)).isEqualTo(doc);
    }

    @Test
    void nullDocDate_returnsNull() {
        assertThat(PaymentTermsDueDateCalculator.derive(null,
                terms(PaymentTermsBasis.DAYS_AFTER_INVOICE, 30))).isNull();
    }

    @Test
    void integerFallback_usedWhenNoTerms() {
        LocalDate doc = LocalDate.of(2026, 1, 1);
        assertThat(PaymentTermsDueDateCalculator.derive(doc, null, 45))
                .isEqualTo(LocalDate.of(2026, 2, 15));
    }

    @Test
    void integerFallback_termsTakePrecedence() {
        PaymentTerms t = terms(PaymentTermsBasis.DAYS_AFTER_INVOICE, 30);
        LocalDate doc = LocalDate.of(2026, 1, 1);
        // master wins over the integer fallback
        assertThat(PaymentTermsDueDateCalculator.derive(doc, t, 45))
                .isEqualTo(LocalDate.of(2026, 1, 31));
    }

    @Test
    void settlementDiscountDueDate_addsDiscountDays() {
        PaymentTerms t = terms(PaymentTermsBasis.DAYS_AFTER_INVOICE, 30);
        t.setDiscountDays(10);
        t.setDiscountPercent(new BigDecimal("2.00"));
        LocalDate doc = LocalDate.of(2026, 1, 1);
        assertThat(PaymentTermsDueDateCalculator.settlementDiscountDueDate(doc, t))
                .isEqualTo(LocalDate.of(2026, 1, 11));
    }

    @Test
    void settlementDiscountDueDate_nullWhenNoDiscountDays() {
        PaymentTerms t = terms(PaymentTermsBasis.DAYS_AFTER_INVOICE, 30);
        assertThat(PaymentTermsDueDateCalculator
                .settlementDiscountDueDate(LocalDate.of(2026, 1, 1), t)).isNull();
    }

    @Test
    void settlementDiscountAmount_computesPercentOfGross() {
        PaymentTerms t = terms(PaymentTermsBasis.DAYS_AFTER_INVOICE, 30);
        t.setDiscountDays(10);
        t.setDiscountPercent(new BigDecimal("2.50"));
        BigDecimal amt = PaymentTermsDueDateCalculator
                .settlementDiscountAmount(new BigDecimal("1000.00"), t);
        assertThat(amt).isEqualByComparingTo("25.0000");
    }

    @Test
    void settlementDiscountAmount_nullWhenNoPercent() {
        PaymentTerms t = terms(PaymentTermsBasis.DAYS_AFTER_INVOICE, 30);
        assertThat(PaymentTermsDueDateCalculator
                .settlementDiscountAmount(new BigDecimal("1000.00"), t)).isNull();
    }

    @Test
    void settlementDiscountAmount_nullWhenGrossZeroOrNull() {
        PaymentTerms t = terms(PaymentTermsBasis.DAYS_AFTER_INVOICE, 30);
        t.setDiscountPercent(new BigDecimal("2.00"));
        assertThat(PaymentTermsDueDateCalculator
                .settlementDiscountAmount(BigDecimal.ZERO, t)).isNull();
        assertThat(PaymentTermsDueDateCalculator
                .settlementDiscountAmount(null, t)).isNull();
    }
}
