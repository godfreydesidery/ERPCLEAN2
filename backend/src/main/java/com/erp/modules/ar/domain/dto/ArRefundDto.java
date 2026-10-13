package com.erp.modules.ar.domain.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * A customer refund just paid (ARC-11). There is no refund table: the refund lives as its journal
 * (DR AR control / CR cash-bank), its OUT cash-book row and the reduced credit on the source
 * document — this DTO reports all three.
 *
 * @param remainingCredit the credit still unused on the source document after this refund
 */
public record ArRefundDto(
        String sourceType,
        String sourceUid,
        String documentNumber,
        Long customerId,
        BigDecimal amount,
        String currency,
        LocalDate refundDate,
        String cashBankAccountUid,
        String journalEntryUid,
        BigDecimal remainingCredit
) {}
