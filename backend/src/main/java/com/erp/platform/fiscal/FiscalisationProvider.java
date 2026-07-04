package com.erp.platform.fiscal;

/**
 * Port for a fiscal-device/tax-authority integration (ADR-0049 §2/§3). Exactly one implementation
 * is active at a time, selected by {@code erp.fiscal.provider} (see {@link FiscalisationProperties}
 * and {@link FiscalisationConfig}).
 *
 * <p>A real adapter (e.g. a future TRA VFD/EFD integration under
 * {@code com.erp.platform.fiscal.tra}) depends only on this interface + {@link FiscalInvoiceDataDto}
 * / {@link FiscalisationResult} — never on the {@code sales} module.
 */
public interface FiscalisationProvider {

    /**
     * Stable identity of this provider, stamped onto {@code fiscal_receipts.provider_code}
     * (e.g. {@code NOT_CONFIGURED}, {@code SIMULATED}, {@code TRA_VFD}).
     */
    String providerCode();

    /**
     * Attempts to fiscalise the given invoice snapshot. MUST NEVER throw for a business decline —
     * an unconfigured device or a rejected/offline/timeout attempt is represented as
     * {@link FiscalisationResult#notConfigured} / {@link FiscalisationResult#failed}, not an
     * exception. Only an unexpected programming error should escape as an exception.
     */
    FiscalisationResult fiscalise(FiscalInvoiceDataDto invoice);
}
