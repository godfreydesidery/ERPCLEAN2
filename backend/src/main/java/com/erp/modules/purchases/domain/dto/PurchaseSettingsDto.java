package com.erp.modules.purchases.domain.dto;

import com.erp.modules.purchases.domain.entity.PurchaseSettings;
import com.erp.modules.purchases.domain.enums.PurchaseVatTreatment;
import com.erp.platform.common.money.CurrencyCode;
import java.math.BigDecimal;

public record PurchaseSettingsDto(
        Long   id,
        String uid,
        Long   companyId,
        boolean poApprovalEnabled,
        BigDecimal poApprovalThresholdAmount,
        String currency,
        // P2 D7 — procurement policy defaults
        Long       defaultPaymentTermsId,
        Long       defaultLocationId,
        BigDecimal matchTolerancePct,
        BigDecimal matchToleranceAbs,
        boolean    autoCloseEnabled,
        boolean    requisitionApprovalEnabled,
        BigDecimal requisitionApprovalThresholdAmount,
        BigDecimal receiptTolerancePct,
        /**
         * How costs entered on goods receipts are read by the PRINTED note (V105, ADR-0063).
         * Never affects posting. Never null — EXCLUSIVE is today's behaviour and the default.
         */
        PurchaseVatTreatment purchaseVatTreatment
) {
    public static PurchaseSettingsDto from(PurchaseSettings s) {
        return new PurchaseSettingsDto(
                s.getId(), s.getUid(), s.getCompanyId(),
                s.isPoApprovalEnabled(), s.getPoApprovalThresholdAmount(), CurrencyCode.value(s.getCurrency()),
                s.getDefaultPaymentTermsId(), s.getDefaultLocationId(),
                s.getMatchTolerancePct(), s.getMatchToleranceAbs(),
                s.isAutoCloseEnabled(), s.isRequisitionApprovalEnabled(),
                s.getRequisitionApprovalThresholdAmount(),
                s.getReceiptTolerancePct(),
                s.getPurchaseVatTreatment());
    }
}
