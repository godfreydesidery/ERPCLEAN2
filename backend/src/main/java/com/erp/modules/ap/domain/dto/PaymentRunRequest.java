package com.erp.modules.ap.domain.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.time.LocalDate;
import java.util.List;

/**
 * Run a payment run: select all matched/approved/partially-paid bills due on or before
 * {@code dueOnOrBefore} for the given supplier (or all suppliers if supplierUid is null).
 */
public record PaymentRunRequest(
        @NotBlank String companyUid,
        /** Optional: restrict to one supplier. Null = all suppliers. */
        String supplierUid,
        /** Select bills with due_date <= this date. Required. */
        @NotNull LocalDate dueOnOrBefore,
        @NotNull LocalDate paymentDate,
        @NotBlank String tenderType,
        String bankReference,
        /**
         * Optional explicit bill list. If non-null/non-empty, only these bills are paid
         * (ignoring the date filter). If null/empty, auto-selects by supplier + due date.
         */
        List<String> billUids,
        /** Optional: uid of the cash/bank account to post to; null = company default (ADR-0016 D-10). */
        String cashBankAccountUid
) {
    /** Back-compat overload: omit cashBankAccountUid → null = company default cash/bank account. */
    public PaymentRunRequest(String companyUid, String supplierUid, LocalDate dueOnOrBefore,
                             LocalDate paymentDate, String tenderType, String bankReference,
                             List<String> billUids) {
        this(companyUid, supplierUid, dueOnOrBefore, paymentDate, tenderType, bankReference,
                billUids, null);
    }
}
