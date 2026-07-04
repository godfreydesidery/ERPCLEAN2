package com.erp.platform.fiscal;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * ADR-0049 §2: the default provider must NEVER fabricate a fiscal number, verification URL, or
 * signature — a fake TRA receipt number is a compliance hazard. Every attempt resolves to
 * {@link FiscalisationOutcome#NOT_CONFIGURED} with a friendly, non-null message.
 */
class NotConfiguredFiscalisationProviderTest {

    private final NotConfiguredFiscalisationProvider provider = new NotConfiguredFiscalisationProvider();

    @Test
    void providerCode_isNotConfigured() {
        assertThat(provider.providerCode()).isEqualTo("NOT_CONFIGURED");
    }

    @Test
    void fiscalise_neverReturnsAFiscalNumberOrVerificationData() {
        FiscalisationResult result = provider.fiscalise(sampleInvoice());

        assertThat(result.outcome()).isEqualTo(FiscalisationOutcome.NOT_CONFIGURED);
        assertThat(result.fiscalNumber()).isNull();
        assertThat(result.verificationUrl()).isNull();
        assertThat(result.signature()).isNull();
        assertThat(result.deviceSerial()).isNull();
        assertThat(result.message()).isNotBlank();
    }

    @Test
    void fiscalise_isDeterministicallyAlwaysNotConfigured_regardlessOfInvoiceContent() {
        // Two very different invoice snapshots — both resolve the same way (no fabrication path).
        FiscalisationResult r1 = provider.fiscalise(sampleInvoice());
        FiscalisationResult r2 = provider.fiscalise(sampleInvoiceWithDifferentTotals());

        assertThat(r1.outcome()).isEqualTo(FiscalisationOutcome.NOT_CONFIGURED);
        assertThat(r2.outcome()).isEqualTo(FiscalisationOutcome.NOT_CONFIGURED);
    }

    private FiscalInvoiceDataDto sampleInvoice() {
        return new FiscalInvoiceDataDto(
                "INVUID0000000000000000001", "INV-0001", 1L, 10L,
                "TIN-001", "VRN-001", "Test Customer", null, "TZS",
                new BigDecimal("1000"), new BigDecimal("180"), new BigDecimal("1180"),
                Instant.now(),
                List.of(new FiscalInvoiceDataDto.Line(
                        "Widget", BigDecimal.ONE, "PCS", new BigDecimal("1000"),
                        new BigDecimal("1000"), new BigDecimal("18.00"), new BigDecimal("180"),
                        "STANDARD")));
    }

    private FiscalInvoiceDataDto sampleInvoiceWithDifferentTotals() {
        return new FiscalInvoiceDataDto(
                "INVUID0000000000000000002", "INV-0002", 1L, 10L,
                "TIN-001", "VRN-001", "Another Customer", null, "TZS",
                new BigDecimal("5000"), new BigDecimal("900"), new BigDecimal("5900"),
                Instant.now(), List.of());
    }
}
