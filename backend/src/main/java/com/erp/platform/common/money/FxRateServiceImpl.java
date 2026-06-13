package com.erp.platform.common.money;

import com.erp.modules.fx.domain.dto.CurrencyDto;
import com.erp.modules.fx.domain.dto.CurrencyRateDto;
import com.erp.modules.fx.domain.dto.UpsertRateRequest;
import com.erp.modules.fx.domain.entity.Currency;
import com.erp.modules.fx.domain.entity.CurrencyRate;
import com.erp.modules.fx.repository.CurrencyRateRepository;
import com.erp.modules.fx.repository.CurrencyRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@inheritDoc}
 *
 * <p>Placement rationale (ArchUnit-safe): this service lives in {@code platform.common.money}
 * alongside {@code CurrencyConversionService} (ADR-0036 D-1: FX is cross-cutting — all modules
 * consume it, so it must not live in any single business module). The ArchUnit rules forbid
 * controllers from touching repositories and services from depending on the web layer — there is
 * no rule preventing a {@code platform} service from depending on module repositories.
 * {@code ScopeGuard} itself (also in {@code platform.security}) already imports dozens of module
 * repositories by the same pattern.
 */
@Service
@Transactional
public class FxRateServiceImpl implements FxRateService {

    private final CurrencyRepository     currencies;
    private final CurrencyRateRepository rates;
    private final CompanyRepository      companies;
    private final ScopeGuard             scopeGuard;

    public FxRateServiceImpl(CurrencyRepository     currencies,
                             CurrencyRateRepository rates,
                             CompanyRepository      companies,
                             ScopeGuard             scopeGuard) {
        this.currencies = currencies;
        this.rates      = rates;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
    }

    // ── Currency master ───────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CurrencyDto> listActiveCurrencies() {
        // Global table — no tenant filter; no assertCanActIn (read-only reference data).
        return currencies.findByActiveTrueOrderByCodeAsc().stream()
                .map(FxRateServiceImpl::toCurrencyDto)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public CurrencyDto getCurrencyByUid(String uid) {
        Currency c = currencies.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("Currency", uid));
        return toCurrencyDto(c);
    }

    // ── Currency rates ────────────────────────────────────────────────────────

    @Override
    public CurrencyRateDto addRate(UpsertRateRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());

        Company company = companies.findById(req.companyId())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(req.companyId())));

        // Validate from-currency is active
        Currency fromCurrency = currencies.findByCode(req.fromCurrency())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown from-currency: " + req.fromCurrency()));
        if (!fromCurrency.isActive()) {
            throw new IllegalArgumentException("Currency is not active: " + req.fromCurrency());
        }

        // Validate to-currency is active
        Currency toCurrency = currencies.findByCode(req.toCurrency())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown to-currency: " + req.toCurrency()));
        if (!toCurrency.isActive()) {
            throw new IllegalArgumentException("Currency is not active: " + req.toCurrency());
        }

        // v1 rule: to_currency must equal the company base currency (D-2)
        String baseCurrency = company.getBaseCurrency();
        if (!baseCurrency.equals(req.toCurrency())) {
            throw new IllegalArgumentException(String.format(
                    "to_currency '%s' must equal company base currency '%s' in v1.",
                    req.toCurrency(), baseCurrency));
        }

        // rate > 0 is validated by @Positive on the request, but double-check at service
        if (req.rate().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("Rate must be greater than zero.");
        }

        String rateType = req.rateType() != null ? req.rateType() : "SPOT";
        Long actorId = actorId();

        CurrencyRate rate = new CurrencyRate(
                req.companyId(), null,
                req.fromCurrency(), req.toCurrency(),
                req.rate(), req.effectiveDate(),
                rateType, req.source(), actorId);

        rate = rates.save(rate);
        return toRateDto(rate);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CurrencyRateDto> listRates(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return rates.findByCompanyIdOrderByEffectiveDateDescIdDesc(companyId, pageable)
                .map(FxRateServiceImpl::toRateDto);
    }

    @Override
    @Transactional(readOnly = true)
    public CurrencyRateDto getRateByUid(String uid) {
        CurrencyRate rate = rates.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("CurrencyRate", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), rate.getCompanyId());
        return toRateDto(rate);
    }

    @Override
    @Transactional(readOnly = true)
    public BigDecimal rateOn(Long companyId, String fromCurrency, String toCurrency, LocalDate asOf) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return rates.findEffective(companyId, fromCurrency, toCurrency, "SPOT", asOf)
                .map(CurrencyRate::getRate)
                .orElseThrow(() -> new FxRateNotFoundException(companyId, fromCurrency, toCurrency, asOf));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    static CurrencyDto toCurrencyDto(Currency c) {
        return new CurrencyDto(
                c.getId(), c.getUid(), c.getCode(), c.getName(),
                c.getSymbol(), c.getMinorUnits(), c.isActive(), c.getStatus());
    }

    static CurrencyRateDto toRateDto(CurrencyRate r) {
        return new CurrencyRateDto(
                r.getId(), r.getUid(), r.getCompanyId(),
                r.getFromCurrency(), r.getToCurrency(),
                r.getRate(), r.getEffectiveDate(),
                r.getRateType(), r.getSource(), r.isActive());
    }
}
