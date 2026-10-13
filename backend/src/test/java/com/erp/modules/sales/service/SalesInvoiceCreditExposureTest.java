package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoicePayment;
import com.erp.modules.sales.domain.enums.TenderType;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * LRB-05: a credit customer's sale is assessed against the credit limit on what the counter
 * tenders leave unpaid, not on its gross. A fully paid sale extends no credit.
 */
class SalesInvoiceCreditExposureTest {

    private final SalesInvoice invoice = mock(SalesInvoice.class);

    private SalesInvoicePayment pay(TenderType type, String amount) {
        return new SalesInvoicePayment(invoice, type, new BigDecimal(amount), null, 1L, 1L);
    }

    @Test
    void fullyPaidInCashAddsNoExposure() {
        assertThat(SalesInvoiceServiceImpl.unpaidAfterCounterPayments(
                new BigDecimal("100000"), List.of(pay(TenderType.CASH, "100000"))))
                .isEqualByComparingTo("0");
    }

    @Test
    void overTenderNeverGoesNegative() {
        assertThat(SalesInvoiceServiceImpl.unpaidAfterCounterPayments(
                new BigDecimal("100000"), List.of(pay(TenderType.CASH, "120000"))))
                .isEqualByComparingTo("0");
    }

    @Test
    void partPaymentLeavesTheResidualAsExposure() {
        assertThat(SalesInvoiceServiceImpl.unpaidAfterCounterPayments(
                new BigDecimal("100000"),
                List.of(pay(TenderType.CASH, "30000"), pay(TenderType.MOBILE_MONEY, "20000"))))
                .isEqualByComparingTo("50000");
    }

    @Test
    void noPaymentMeansTheWholeGrossIsCredit() {
        assertThat(SalesInvoiceServiceImpl.unpaidAfterCounterPayments(
                new BigDecimal("100000"), List.of()))
                .isEqualByComparingTo("100000");
    }

    @Test
    void changeGivenIsNotCountedAsPaid() {
        SalesInvoicePayment cash = pay(TenderType.CASH, "100000");
        cash.setChangeAmount(new BigDecimal("10000"));
        assertThat(SalesInvoiceServiceImpl.unpaidAfterCounterPayments(
                new BigDecimal("100000"), List.of(cash)))
                .isEqualByComparingTo("10000");
    }
}
