package com.erp.modules.ap.domain.dto;

import com.erp.modules.ap.domain.enums.ApPaymentKind;
import com.erp.modules.ap.domain.enums.ApPaymentStatus;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/**
 * Response DTO for an AP payment.
 * D-7: exposes whtAmount and whtTransactionUid stamped at payment time.
 * D-9: exposes unallocatedAmount, status, chequeUid for on-account tracking.
 *
 * <p><b>The cash/bank account fields answer "which one did this draw on?"</b> (UAT, 2026-08). A
 * payment run accepts an OPTIONAL {@code cashBankAccountUid} and silently falls back to the
 * company default when it is omitted (ADR-0016 D-10), yet the posted response never stated which
 * account it had actually used. Harmless with one bank account; at group scale a run can post from
 * the wrong account and nothing says so until reconciliation. These four fields echo the account
 * the money actually left, resolved from the {@code cash_bank_account_id} stamped on the payment
 * row — not from the request, so a defaulted run reports the default it got:
 * <ul>
 *   <li>{@code cashBankAccountId} / {@code cashBankAccountUid} — the identity to address it by;</li>
 *   <li>{@code cashBankAccountName} — the human label ("Main NMB Current");</li>
 *   <li>{@code cashBankAccountNumber} — the bank account number, which is what tells two accounts
 *       at the same bank apart. {@code null} for a pure cash account, which has none.</li>
 * </ul>
 * All four are {@code null} on a payment that predates the account being stamped, or when the
 * account row can no longer be read.
 */
public record ApPaymentDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        Long supplierId,
        String paymentNumber,
        ApPaymentKind kind,
        LocalDate paymentDate,
        BigDecimal amount,
        String currency,
        String tenderType,
        String bankReference,
        String glEntryUid,
        // UAT 2026-08: which cash/bank account the money actually left
        Long cashBankAccountId,
        String cashBankAccountUid,
        String cashBankAccountName,
        String cashBankAccountNumber,
        // D-7: WHT withheld on this payment
        BigDecimal whtAmount,
        String whtTransactionUid,
        // D-9: on-account tracking
        BigDecimal unallocatedAmount,
        ApPaymentStatus status,
        String chequeUid,
        // ADR-0041 D3: grouping run id (null for single-bill payments)
        Long paymentRunId,
        List<PaymentAllocationDto> allocations
) {
    public record PaymentAllocationDto(
            Long id,
            Long supplierBillId,
            String supplierBillUid,
            BigDecimal allocatedAmount
    ) {}
}
