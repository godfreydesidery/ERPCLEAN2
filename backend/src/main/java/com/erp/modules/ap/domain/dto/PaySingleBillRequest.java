package com.erp.modules.ap.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Pay a single bill (fully or partly).
 * whtTypeUid + whtAmount are optional WHT fields (ADR-0017 D-9): when present the WHT leg is
 * captured and the cash CR is reduced by whtAmount.
 */
public record PaySingleBillRequest(
        @NotBlank String companyUid,
        /** uid of the supplier_bill to pay. */
        @NotBlank String supplierBillUid,
        @NotNull @Positive BigDecimal amount,
        @NotNull LocalDate paymentDate,
        @NotBlank String tenderType,
        String bankReference,
        /** Optional: uid of the cash/bank account to post to; null = company default (ADR-0016 D-10). */
        String cashBankAccountUid,
        /** Optional: uid of the WhtType to use for WHT_ON_PAYMENT capture (ADR-0017 D-9). */
        String whtTypeUid,
        /** Optional: WHT amount withheld from the supplier (ADR-0017 D-9). */
        BigDecimal whtAmount
) {
    /** Back-compat overload: omit cashBankAccountUid → null; no WHT. */
    public PaySingleBillRequest(String companyUid, String supplierBillUid, BigDecimal amount,
                                LocalDate paymentDate, String tenderType, String bankReference) {
        this(companyUid, supplierBillUid, amount, paymentDate, tenderType, bankReference,
                null, null, null);
    }

    /** Back-compat overload: include cashBankAccountUid but no WHT. */
    public PaySingleBillRequest(String companyUid, String supplierBillUid, BigDecimal amount,
                                LocalDate paymentDate, String tenderType, String bankReference,
                                String cashBankAccountUid) {
        this(companyUid, supplierBillUid, amount, paymentDate, tenderType, bankReference,
                cashBankAccountUid, null, null);
    }
}
