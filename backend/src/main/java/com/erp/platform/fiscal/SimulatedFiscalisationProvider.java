package com.erp.platform.fiscal;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Dev/QA-only simulated fiscalisation provider (ADR-0049 §2). Active ONLY when
 * {@code erp.fiscal.provider=simulated} — never the prod default; {@link FiscalisationConfig} fails
 * application startup if this is selected under the {@code prod} profile.
 *
 * <p>Produces a deterministic fake fiscal number derived from the invoice's own identity
 * (invoice number, falling back to the invoice uid) — NOT {@code Instant.now()} / {@code Random} —
 * so the same invoice always simulates to the same number and unit tests can assert on it. The
 * number/URL/signature are all visibly marked {@code SIMULATED}; never confusable with a real
 * {@code TRA_VFD} receipt.
 */
@Component
@ConditionalOnProperty(name = "erp.fiscal.provider", havingValue = "simulated")
public class SimulatedFiscalisationProvider implements FiscalisationProvider {

    public static final String PROVIDER_CODE = "SIMULATED";

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public FiscalisationResult fiscalise(FiscalInvoiceDataDto invoice) {
        String seed = (invoice.invoiceNumber() != null && !invoice.invoiceNumber().isBlank())
                ? invoice.invoiceNumber()
                : invoice.invoiceUid();
        String digits = deterministicDigits(seed);
        String fiscalNumber = "SIM-" + digits;
        String verificationUrl = "https://simulated-efd.example.test/verify/" + fiscalNumber;
        String signature = "SIMSIG-" + deterministicDigits("sig:" + seed);
        String deviceSerial = "SIM-DEVICE-0001";
        // issuedAt = null → the service stamps the server clock (no fabricated device time).
        return FiscalisationResult.issued(
                PROVIDER_CODE, fiscalNumber, verificationUrl, signature, deviceSerial, null);
    }

    /** SHA-256 of {@code seed}, reduced to a stable 10-digit decimal string. Never time/random-based. */
    private static String deterministicDigits(String seed) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(seed.getBytes(StandardCharsets.UTF_8));
            long value = 0;
            for (int i = 0; i < 8 && i < hash.length; i++) {
                value = (value << 8) | (hash[i] & 0xFF);
            }
            value = Math.abs(value % 10_000_000_000L);
            return String.format("%010d", value);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandatory JDK algorithm — this cannot happen. Fail loudly if it ever does.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
