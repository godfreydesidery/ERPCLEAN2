package com.erp.platform.fiscal;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * The honest default fiscalisation provider (ADR-0049 §2). Active when {@code erp.fiscal.provider}
 * is unset or {@code none}. NEVER fabricates a fiscal number, verification URL, or signature — a
 * fake TRA receipt number would be a compliance/legal hazard. Every attempt resolves to
 * {@link FiscalisationOutcome#NOT_CONFIGURED}, honestly recording that fiscalisation was attempted
 * with no device/adapter wired.
 */
@Component
@ConditionalOnProperty(name = "erp.fiscal.provider", havingValue = "none", matchIfMissing = true)
public class NotConfiguredFiscalisationProvider implements FiscalisationProvider {

    public static final String PROVIDER_CODE = "NOT_CONFIGURED";

    private static final String MESSAGE =
            "No fiscal device is configured for this environment; contact your administrator.";

    @Override
    public String providerCode() {
        return PROVIDER_CODE;
    }

    @Override
    public FiscalisationResult fiscalise(FiscalInvoiceDataDto invoice) {
        return FiscalisationResult.notConfigured(MESSAGE);
    }
}
