package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.approvals.service.ApprovalEngine;
import com.erp.modules.purchases.domain.entity.PurchaseOrder;
import com.erp.modules.purchases.domain.entity.PurchaseSettings;
import com.erp.modules.purchases.domain.enums.PurchaseOrderOrigin;
import com.erp.modules.purchases.repository.PurchaseSettingsRepository;
import com.erp.modules.purchases.service.PoApprovalGate.ApprovalRequirement;
import com.erp.modules.purchases.service.PoApprovalGate.Decision;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the PO approval gate's verdict AND its reason (UAT wave 1, finding c).
 *
 * <p>The reason is the point: a live company had approval switched off with no threshold ever set,
 * yet every buyer was told their order was "below the approval threshold" — two managers read that
 * as proof a ceiling existed. "Nothing is checked" and "checked, and this one is small" must be
 * distinguishable by the caller that has to explain itself.
 */
class PoApprovalGateTest {

    private static final long COMPANY_ID = 10L;

    private PurchaseSettingsRepository settings;
    private PoApprovalGate gate;
    private com.erp.platform.common.money.CurrencyConversionService fx;

    @BeforeEach
    void setUp() {
        settings = mock(PurchaseSettingsRepository.class);
        fx = mock(com.erp.platform.common.money.CurrencyConversionService.class);
        gate = new PoApprovalGate(settings, mock(ApprovalEngine.class), fx);
    }

    /** PUR-22: a USD order is measured in the threshold's currency, not by its bare number. */
    @Test
    void foreignCurrencyOrder_isConvertedBeforeTheThresholdCompare() {
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(cfg(true, new BigDecimal("5000000.0000"))));
        PurchaseOrder usd = po(new BigDecimal("9000.00"));
        when(usd.getCurrency()).thenReturn(CurrencyCode.of("USD"));
        when(fx.convert(org.mockito.ArgumentMatchers.eq(new BigDecimal("9000.00")),
                org.mockito.ArgumentMatchers.eq("USD"), org.mockito.ArgumentMatchers.eq("TZS"),
                org.mockito.ArgumentMatchers.eq(COMPANY_ID), any()))
                .thenReturn(new com.erp.platform.common.money.ConvertedAmount(
                        new BigDecimal("22500000"), new BigDecimal("2500"), java.time.Instant.now()));

        assertThat(gate.evaluate(usd).requirement())
                .as("USD 9,000 is about TZS 22.5M — above a TZS 5M ceiling")
                .isEqualTo(ApprovalRequirement.REQUIRED);
    }

    @Test
    void foreignCurrencyOrder_withNoRate_failsClosedToApprovalRequired() {
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(cfg(true, new BigDecimal("5000000.0000"))));
        PurchaseOrder usd = po(new BigDecimal("100.00"));
        when(usd.getCurrency()).thenReturn(CurrencyCode.of("USD"));
        when(fx.convert(any(), any(), any(), any(), any()))
                .thenThrow(new com.erp.platform.common.money.FxRateNotFoundException(
                        COMPANY_ID, "USD", "TZS", java.time.LocalDate.now()));

        assertThat(gate.evaluate(usd).requirement()).isEqualTo(ApprovalRequirement.REQUIRED);
    }

    @Test
    void sameCurrencyOrder_isComparedWithoutAnyConversion() {
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(cfg(true, new BigDecimal("5000000.0000"))));
        PurchaseOrder tzs = po(new BigDecimal("100000.00"));
        when(tzs.getCurrency()).thenReturn(CurrencyCode.of("TZS"));

        assertThat(gate.evaluate(tzs).requirement()).isEqualTo(ApprovalRequirement.BELOW_THRESHOLD);
        verify(fx, never()).convert(any(), any(), any(), any(), any());
    }

    @Test
    void noSettingsRow_isReportedAsSwitchedOffCompanyWide_notAsBelowThreshold() {
        when(settings.findByCompanyId(COMPANY_ID)).thenReturn(Optional.empty());

        Decision decision = gate.evaluate(po(new BigDecimal("100000000.00")));

        assertThat(decision.requirement()).isEqualTo(ApprovalRequirement.DISABLED_COMPANY_WIDE);
        assertThat(decision.required()).isFalse();
        assertThat(decision.thresholdAmount()).isNull();
    }

    @Test
    void approvalSwitchedOff_isReportedAsSwitchedOffCompanyWide_evenWithAThresholdOnTheRow() {
        // The exact live shape that produced the misleading message, plus a leftover threshold:
        // a value that is not being applied must not be reported as if it were.
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(cfg(false, new BigDecimal("5000000.0000"))));

        Decision decision = gate.evaluate(po(new BigDecimal("100000000.00")));

        assertThat(decision.requirement()).isEqualTo(ApprovalRequirement.DISABLED_COMPANY_WIDE);
        assertThat(decision.thresholdAmount()).isNull();
    }

    @Test
    void approvalOn_orderUnderTheCeiling_isBelowThreshold_andCarriesTheCeiling() {
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(cfg(true, new BigDecimal("5000000.0000"))));

        Decision decision = gate.evaluate(po(new BigDecimal("100000.00")));

        assertThat(decision.requirement()).isEqualTo(ApprovalRequirement.BELOW_THRESHOLD);
        assertThat(decision.required()).isFalse();
        assertThat(decision.thresholdAmount()).isEqualByComparingTo("5000000");
        assertThat(decision.thresholdCurrency()).isEqualTo("TZS");
    }

    @Test
    void approvalOn_orderAtOrAboveTheCeiling_requiresApproval() {
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(cfg(true, new BigDecimal("5000000.0000"))));

        assertThat(gate.evaluate(po(new BigDecimal("5000000.00"))).requirement())
                .isEqualTo(ApprovalRequirement.REQUIRED);
        assertThat(gate.requiresApproval(po(new BigDecimal("100000000.00")), null)).isTrue();
    }

    @Test
    void approvalOn_withNoCeilingSet_requiresApprovalForEveryOrder() {
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(cfg(true, null)));

        assertThat(gate.evaluate(po(BigDecimal.ONE)).requirement())
                .isEqualTo(ApprovalRequirement.REQUIRED);
    }

    @Test
    void directReceiptOrder_isExempt_withoutEvenReadingSettings() {
        PurchaseOrder po = po(new BigDecimal("100000000.00"));
        when(po.getOrigin()).thenReturn(PurchaseOrderOrigin.DIRECT_RECEIPT);

        Decision decision = gate.evaluate(po);

        assertThat(decision.requirement()).isEqualTo(ApprovalRequirement.EXEMPT_DIRECT_RECEIPT);
        assertThat(decision.required()).isFalse();
        verify(settings, never()).findByCompanyId(any());
    }

    @Test
    void requiresApproval_staysInAgreementWithTheReasonedVerdict() {
        when(settings.findByCompanyId(COMPANY_ID))
                .thenReturn(Optional.of(cfg(true, new BigDecimal("5000000.0000"))));
        PurchaseOrder small = po(new BigDecimal("100000.00"));

        assertThat(gate.requiresApproval(small, null)).isEqualTo(gate.evaluate(small).required());
    }

    // -------------------------------------------------------------------------

    private static PurchaseOrder po(BigDecimal total) {
        PurchaseOrder po = mock(PurchaseOrder.class);
        when(po.getCompanyId()).thenReturn(COMPANY_ID);
        when(po.getOrderTotalAmount()).thenReturn(total);
        return po;
    }

    private static PurchaseSettings cfg(boolean enabled, BigDecimal threshold) {
        PurchaseSettings s = new PurchaseSettings(COMPANY_ID, 1L);
        s.setPoApprovalEnabled(enabled);
        s.setPoApprovalThresholdAmount(threshold);
        s.setCurrency(CurrencyCode.of("TZS"));
        return s;
    }
}
