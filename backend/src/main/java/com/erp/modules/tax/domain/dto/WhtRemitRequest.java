package com.erp.modules.tax.domain.dto;

import jakarta.validation.constraints.NotBlank;
import java.time.LocalDate;

/**
 * Request to mark a WHT transaction as remitted to the tax authority (ADR-0040 D-7).
 * {@code remittancePeriod} is the YYYY-MM period; {@code remittanceRef} is the authority acknowledgement.
 *
 * <p>ACC-07: when {@code cashBankAccountUid} is given the remittance is also BOOKED — DR WHT Payable
 * / CR that account's GL, dated {@code paymentDate} (today when omitted). Without it the call only
 * flags the row, as before (for a payment already recorded some other way).
 */
public record WhtRemitRequest(
        @NotBlank String remittancePeriod,
        @NotBlank String remittanceRef,
        String cashBankAccountUid,
        LocalDate paymentDate) {

    /** Flag-only remittance (the original shape). */
    public WhtRemitRequest(String remittancePeriod, String remittanceRef) {
        this(remittancePeriod, remittanceRef, null, null);
    }
}
