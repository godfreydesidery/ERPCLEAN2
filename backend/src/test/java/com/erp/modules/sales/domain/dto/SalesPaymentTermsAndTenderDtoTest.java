package com.erp.modules.sales.domain.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.sales.domain.enums.TenderType;
import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Pure unit tests for the ADR-0041 sales DTO completion-wiring (no Spring / no DB):
 * <ul>
 *   <li>{@link CreateSalesInvoiceRequest} — back-compat 6-arg ctor leaves paymentTermsUid null;
 *       7-arg ctor carries the override.</li>
 *   <li>{@link FinaliseInvoiceRequest} — no-arg ctor leaves paymentTermsUid null; 1-arg carries it.</li>
 *   <li>{@link AddPaymentRequest} — back-compat 4-arg ctor leaves all structured instrument refs null;
 *       8-arg ctor carries them.</li>
 *   <li>{@link TenderType} — CHEQUE and CARD are now admitted (ADR-0041 D3).</li>
 * </ul>
 */
class SalesPaymentTermsAndTenderDtoTest {

    @Test
    void createSalesInvoiceRequest_backCompatCtor_paymentTermsUidNull() {
        CreateSalesInvoiceRequest req = new CreateSalesInvoiceRequest(
                "co", "cust", "agent", "TZS", "notes", "route");
        assertThat(req.paymentTermsUid()).isNull();
    }

    @Test
    void createSalesInvoiceRequest_fullCtor_carriesPaymentTermsUid() {
        CreateSalesInvoiceRequest req = new CreateSalesInvoiceRequest(
                "co", "cust", "agent", "TZS", "notes", "route", "TERM-30");
        assertThat(req.paymentTermsUid()).isEqualTo("TERM-30");
    }

    @Test
    void finaliseInvoiceRequest_noArgCtor_paymentTermsUidNull() {
        assertThat(new FinaliseInvoiceRequest().paymentTermsUid()).isNull();
    }

    @Test
    void finaliseInvoiceRequest_overrideCtor_carriesPaymentTermsUid() {
        assertThat(new FinaliseInvoiceRequest("TERM-60").paymentTermsUid()).isEqualTo("TERM-60");
    }

    @Test
    void addPaymentRequest_backCompatCtor_instrumentRefsNull() {
        AddPaymentRequest req = new AddPaymentRequest(
                TenderType.CASH, new BigDecimal("100"), "TZS", "ref");
        assertThat(req.cashBankAccountId()).isNull();
        assertThat(req.chequeId()).isNull();
        assertThat(req.mobileMoneyRef()).isNull();
        assertThat(req.cardRef()).isNull();
    }

    @Test
    void addPaymentRequest_fullCtor_carriesInstrumentRefs() {
        AddPaymentRequest req = new AddPaymentRequest(
                TenderType.CHEQUE, new BigDecimal("100"), "TZS", "ref",
                7L, 9L, "MM-REF", "CARD-AUTH");
        assertThat(req.cashBankAccountId()).isEqualTo(7L);
        assertThat(req.chequeId()).isEqualTo(9L);
        assertThat(req.mobileMoneyRef()).isEqualTo("MM-REF");
        assertThat(req.cardRef()).isEqualTo("CARD-AUTH");
    }

    @Test
    void tenderType_admitsChequeAndCard() {
        assertThat(TenderType.valueOf("CHEQUE")).isEqualTo(TenderType.CHEQUE);
        assertThat(TenderType.valueOf("CARD")).isEqualTo(TenderType.CARD);
        assertThat(TenderType.values()).containsExactly(
                TenderType.CASH, TenderType.MOBILE_MONEY, TenderType.CHEQUE, TenderType.CARD);
    }
}
