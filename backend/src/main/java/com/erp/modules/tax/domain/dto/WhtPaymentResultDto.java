package com.erp.modules.tax.domain.dto;

import java.math.BigDecimal;

/** Outcome of a WHT payment to TRA (ACC-07): how many certificates it remitted and for how much. */
public record WhtPaymentResultDto(
        int certificatesRemitted,
        /** Base-currency amount paid — what DR WHT Payable / CR bank moved. */
        BigDecimal amountPaid,
        String cashTransactionUid
) {}
