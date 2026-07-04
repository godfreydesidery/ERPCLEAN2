package com.erp.platform.fiscal;

import java.time.Instant;

/**
 * Outcome of a single {@link FiscalisationProvider#fiscalise} attempt (ADR-0049 §2/§4). A provider
 * must never throw for a business decline — an unconfigured or failed attempt is represented as a
 * value here, never an exception.
 *
 * <p>{@code providerCode} is populated only for {@link FiscalisationOutcome#ISSUED} (a provider
 * asserting the identity of the device that actually issued the receipt); the service stamps
 * {@code fiscal_receipts.provider_code} from the bean's own {@link FiscalisationProvider#providerCode()}
 * regardless of outcome, so a NOT_CONFIGURED/FAILED row is never left without a provider identity.
 *
 * <p>{@code issuedAt} is the DEVICE/TRA-reported issue time when a real adapter can supply it; it may
 * be {@code null} (the service then falls back to the server clock), so the seam is drop-in for a
 * future adapter that reports a true device timestamp.
 */
public record FiscalisationResult(
        FiscalisationOutcome outcome,
        String providerCode,
        String fiscalNumber,
        String verificationUrl,
        String signature,
        String deviceSerial,
        Instant issuedAt,
        String message) {

    /**
     * A real fiscal number + verification data were obtained. Terminal — never re-attempted.
     * {@code issuedAt} is the device-reported time, or {@code null} to let the service stamp the
     * server clock (current NotConfigured/Simulated providers pass null).
     */
    public static FiscalisationResult issued(String providerCode, String fiscalNumber,
            String verificationUrl, String signature, String deviceSerial, Instant issuedAt) {
        return new FiscalisationResult(FiscalisationOutcome.ISSUED, providerCode, fiscalNumber,
                verificationUrl, signature, deviceSerial, issuedAt, null);
    }

    /** No device/adapter is configured; nothing was fabricated. Retryable. */
    public static FiscalisationResult notConfigured(String message) {
        return new FiscalisationResult(
                FiscalisationOutcome.NOT_CONFIGURED, null, null, null, null, null, null, message);
    }

    /** An adapter attempted and errored (offline/rejected/timeout). Retryable. */
    public static FiscalisationResult failed(String message) {
        return new FiscalisationResult(
                FiscalisationOutcome.FAILED, null, null, null, null, null, null, message);
    }
}
