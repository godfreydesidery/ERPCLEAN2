package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.entity.FiscalReceipt;
import java.time.Instant;

/**
 * Wire shape for a fiscal receipt (ADR-0049 §7). {@code errorDetail} is the friendly, non-leaky
 * message set by the provider ({@link com.erp.platform.fiscal.FiscalisationResult}) — never raw
 * exception text. Deliberately omits {@code signature} (device-internal, no UI need).
 */
public record FiscalReceiptDto(
        String uid,
        String invoiceUid,
        String status,
        String providerCode,
        String fiscalNumber,
        String verificationUrl,
        String deviceSerial,
        Instant issuedAt,
        int attemptCount,
        String errorDetail,
        Long version,
        Instant createdAt) {

    public static FiscalReceiptDto from(FiscalReceipt r, String invoiceUid) {
        return new FiscalReceiptDto(
                r.getUid(),
                invoiceUid,
                r.getStatus().name(),
                r.getProviderCode(),
                r.getFiscalNumber(),
                r.getVerificationUrl(),
                r.getDeviceSerial(),
                r.getIssuedAt(),
                r.getAttemptCount(),
                r.getErrorDetail(),
                r.getVersion(),
                r.getCreatedAt());
    }
}
