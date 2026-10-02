package com.erp.platform.common.money;

import com.erp.modules.fx.repository.CurrencyRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Reads minor units from the {@code currencies} master (global reference data, not tenant data),
 * falling back to {@link CurrencyMinorUnits#fallback(String)} for a code the master does not list.
 *
 * <p>Placed in {@code platform.common.money} beside {@link CurrencyConversionServiceImpl}, which
 * reads the same master for the same reason, so every module can round in a document's own
 * currency without importing the fx module.
 */
@Component
public class CurrencyMinorUnitsImpl implements CurrencyMinorUnits {

    private final CurrencyRepository currencies;

    public CurrencyMinorUnitsImpl(CurrencyRepository currencies) {
        this.currencies = currencies;
    }

    @Override
    @Transactional(readOnly = true)
    public int of(String currencyCode) {
        if (currencyCode == null || currencyCode.isBlank()) {
            return CurrencyMinorUnits.fallback(currencyCode);
        }
        String code = currencyCode.strip().toUpperCase();
        return currencies.findByCode(code)
                .map(c -> (int) c.getMinorUnits())
                .orElseGet(() -> CurrencyMinorUnits.fallback(code));
    }
}
