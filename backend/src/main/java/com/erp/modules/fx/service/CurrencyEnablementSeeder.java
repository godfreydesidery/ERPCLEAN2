package com.erp.modules.fx.service;

import com.erp.modules.fx.domain.entity.CompanyCurrency;
import com.erp.modules.fx.repository.CompanyCurrencyRepository;
import com.erp.modules.fx.repository.CurrencyRepository;
import com.erp.platform.common.money.CurrencyCode;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Seeds the bootstrap company's {@code company_currency} rows from {@link
 * com.erp.platform.bootstrap.BootstrapProperties} (ADR-0039 D-9).
 *
 * <p>Called by {@link com.erp.platform.bootstrap.BootstrapRunner} after the company is persisted.
 * Idempotent: skips rows that already exist.
 *
 * <p>Day-1 result: every company starts with the Classic EAC-6 + USD + EUR enabled and
 * TZS (or configured base) as both base and default document currency.
 * No {@code branch_currency} rows are seeded — branches inherit the company list (ADR-0039 D-7).
 * No FX rates are seeded (a rate is required only at the first foreign document — ADR-0039 D-9).
 */
@Component
public class CurrencyEnablementSeeder {

    private static final Logger log = LoggerFactory.getLogger(CurrencyEnablementSeeder.class);

    private final CompanyCurrencyRepository companyCurrencies;
    private final CurrencyRepository        currencies;

    public CurrencyEnablementSeeder(CompanyCurrencyRepository companyCurrencies,
                                     CurrencyRepository        currencies) {
        this.companyCurrencies = companyCurrencies;
        this.currencies        = currencies;
    }

    /**
     * Seeds {@code company_currency} rows for a new company.
     *
     * @param companyId   the newly-created company id
     * @param base        ISO-4217 code for the ledger base currency (also seeded as default
     *                    if {@code defaultCurrency} matches)
     * @param defaultCcy  ISO-4217 code for the default document currency
     * @param enabledSet  full set of codes to enable (must include {@code base})
     */
    @Transactional
    public void seedDefaults(Long companyId, String base, String defaultCcy,
                              List<String> enabledSet) {
        String effectiveBase    = base.strip().toUpperCase();
        String effectiveDefault = defaultCcy.strip().toUpperCase();

        // Ensure base is always in the enabled set (invariant D-7)
        List<String> codes = enabledSet.stream()
                .map(s -> s.strip().toUpperCase())
                .distinct()
                .toList();
        if (!codes.contains(effectiveBase)) {
            codes = new java.util.ArrayList<>(codes);
            codes.add(effectiveBase);
        }

        for (String code : codes) {
            if (companyCurrencies.existsByCompanyIdAndCurrencyCode(
                    companyId, CurrencyCode.of(code))) {
                log.debug("CurrencyEnablementSeeder: {} already seeded for company {} — skip.",
                        code, companyId);
                continue;
            }
            // Check the code exists in the global catalog (skip unknown codes gracefully)
            if (currencies.findByCode(code).isEmpty()) {
                log.warn("CurrencyEnablementSeeder: currency '{}' not in catalog — skipping for company {}.",
                        code, companyId);
                continue;
            }

            boolean isDefault = code.equals(effectiveDefault);
            CompanyCurrency cc = new CompanyCurrency(
                    companyId,
                    CurrencyCode.of(code),
                    isDefault,
                    true,       // active
                    null);      // system seed, no actor
            companyCurrencies.save(cc);
            log.info("CurrencyEnablementSeeder: enabled '{}' for company {} (default={}).",
                    code, companyId, isDefault);
        }

        // If no row was seeded as default (e.g. configured defaultCurrency not in enabled set),
        // fall back to marking the base row as default (invariant: always exactly one default).
        if (companyCurrencies.findByCompanyIdAndIsDefaultTrue(companyId).isEmpty()) {
            companyCurrencies
                    .findByCompanyIdAndCurrencyCode(companyId, CurrencyCode.of(effectiveBase))
                    .ifPresent(cc -> {
                        cc.setDefault(true);
                        log.warn("CurrencyEnablementSeeder: no default set — falling back to base '{}' for company {}.",
                                effectiveBase, companyId);
                    });
        }
    }
}
