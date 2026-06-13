package com.erp.platform.common.money;

import com.erp.modules.fx.repository.CurrencyRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@inheritDoc}
 *
 * <p><b>Rate direction (applied here once, never ad hoc):</b>
 * {@code baseAmount = faceAmount × rate} where {@code rate} = units of base per 1 unit of foreign.
 * Example: 1 USD = 2500 TZS ⇒ rate=2500, face=100 USD ⇒ base=250,000 TZS.
 *
 * <p><b>Rounding:</b> HALF_UP to the <em>target</em> currency's {@code minor_units}
 * (from the {@code currencies} master, ADR-0036 D-8 / ADR-0005 D-2).
 *
 * <p><b>ArchUnit-safe placement:</b> lives in {@code platform.common.money}. The ArchUnit rules
 * only forbid controllers→repositories and services→controllers. {@code ScopeGuard} in the same
 * platform package already imports module repositories; this service follows the same pattern.
 */
@Service
@Transactional(readOnly = true)
public class CurrencyConversionServiceImpl implements CurrencyConversionService {

    private final FxRateService      fxRateService;
    private final CurrencyRepository currencies;
    private final CompanyRepository  companies;
    private final ScopeGuard         scopeGuard;

    public CurrencyConversionServiceImpl(FxRateService      fxRateService,
                                         CurrencyRepository currencies,
                                         CompanyRepository  companies,
                                         ScopeGuard         scopeGuard) {
        this.fxRateService = fxRateService;
        this.currencies    = currencies;
        this.companies     = companies;
        this.scopeGuard    = scopeGuard;
    }

    @Override
    public ConvertedAmount toBase(BigDecimal faceAmount, String fromCode,
                                  Long companyId, LocalDate asOf) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        String baseCurrency = resolveBaseCurrency(companyId);
        return convert(faceAmount, fromCode, baseCurrency, companyId, asOf);
    }

    @Override
    public ConvertedAmount convert(BigDecimal faceAmount, String fromCode, String toCode,
                                   Long companyId, LocalDate asOf) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // Rule 1 — identity short-circuit: from==to ⇒ no rate-table lookup, rate=1 (D-1)
        if (fromCode.equals(toCode)) {
            return ConvertedAmount.identity(faceAmount);
        }

        // Rule 2 — foreign conversion: look up effective rate, apply, round HALF_UP (D-1/D-8)
        BigDecimal rate = fxRateService.rateOn(companyId, fromCode, toCode, asOf);
        // base_amount = face_amount × rate   (multiply, never divide — the fixed convention)
        int toMinorUnits = resolveMinorUnits(toCode);
        BigDecimal baseAmount = faceAmount.multiply(rate)
                .setScale(toMinorUnits, RoundingMode.HALF_UP);

        return new ConvertedAmount(baseAmount, rate, Instant.now());
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private String resolveBaseCurrency(Long companyId) {
        return companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));
    }

    /**
     * Minor units from the {@code currencies} master. Falls back to 2 if the code is not in the
     * master (defensive — the master is seeded for all supported codes at launch).
     */
    private int resolveMinorUnits(String currencyCode) {
        return currencies.findByCode(currencyCode)
                .map(c -> (int) c.getMinorUnits())
                .orElse(2);
    }
}
