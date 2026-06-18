package com.erp.modules.sales.domain.entity;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.sales.domain.dto.SalesInvoicePaymentDto;
import com.erp.modules.sales.domain.enums.TenderType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure unit test (no Spring / no DB) for the ADR-0041 D3 structured instrument links / refs on
 * {@link SalesInvoicePayment} and their round-trip through {@link SalesInvoicePaymentDto#from}.
 */
class SalesInvoicePaymentInstrumentTest {

    private static SalesInvoicePayment payment(TenderType tender) {
        SalesInvoice inv = new SalesInvoice(10L, 20L, 30L, 40L, "TZS", 1L);
        return new SalesInvoicePayment(inv, tender, new BigDecimal("100.00"), "ref", 1L, 1L);
    }

    @Test
    void instrumentSetters_persistAllStructuredRefs() {
        SalesInvoicePayment p = payment(TenderType.CHEQUE);
        p.setCashBankAccountId(11L);
        p.setChequeId(22L);
        p.setMobileMoneyRef("MM-1");
        p.setCardRef("CARD-1");

        assertThat(p.getCashBankAccountId()).isEqualTo(11L);
        assertThat(p.getChequeId()).isEqualTo(22L);
        assertThat(p.getMobileMoneyRef()).isEqualTo("MM-1");
        assertThat(p.getCardRef()).isEqualTo("CARD-1");
    }

    @Test
    void dtoFrom_mapsStructuredInstrumentRefs() {
        SalesInvoicePayment p = payment(TenderType.CARD);
        p.setCashBankAccountId(7L);
        p.setCardRef("CARD-AUTH-123");

        SalesInvoicePaymentDto dto = SalesInvoicePaymentDto.from(p);

        assertThat(dto.tenderType()).isEqualTo(TenderType.CARD);
        assertThat(dto.cashBankAccountId()).isEqualTo(7L);
        assertThat(dto.cardRef()).isEqualTo("CARD-AUTH-123");
        assertThat(dto.chequeId()).isNull();
        assertThat(dto.mobileMoneyRef()).isNull();
    }
}
