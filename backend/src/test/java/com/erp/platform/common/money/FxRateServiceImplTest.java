package com.erp.platform.common.money;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.fx.domain.dto.UpsertRateRequest;
import com.erp.modules.fx.domain.entity.Currency;
import com.erp.modules.fx.domain.entity.CurrencyRate;
import com.erp.modules.fx.repository.CurrencyRateRepository;
import com.erp.modules.fx.repository.CurrencyRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link FxRateServiceImpl} — error-message-hygiene defect D2: adding a rate that
 * clashes with the real unique key ({@code uq_currency_rate}: company_id, from_currency,
 * to_currency, effective_date, rate_type) must throw a friendly {@link ConflictException} naming
 * the currency pair and date, pre-checked before save, never the generic DB-constraint catch-all.
 */
class FxRateServiceImplTest {

    private static final Long COMPANY_ID = 10L;
    private static final LocalDate RATE_DATE = LocalDate.of(2026, 1, 15);

    private CurrencyRepository currencies;
    private CurrencyRateRepository rates;
    private CompanyRepository companies;
    private ScopeGuard scopeGuard;
    private FxRateServiceImpl service;

    @BeforeEach
    void setUp() {
        currencies = mock(CurrencyRepository.class);
        rates = mock(CurrencyRateRepository.class);
        companies = mock(CompanyRepository.class);
        scopeGuard = mock(ScopeGuard.class);
        service = new FxRateServiceImpl(currencies, rates, companies, scopeGuard);

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(company.getBaseCurrency()).thenReturn("TZS");
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        Currency usd = mock(Currency.class);
        when(usd.isActive()).thenReturn(true);
        when(currencies.findByCode("USD")).thenReturn(Optional.of(usd));

        Currency tzs = mock(Currency.class);
        when(tzs.isActive()).thenReturn(true);
        when(currencies.findByCode("TZS")).thenReturn(Optional.of(tzs));

        RequestContext.set(new RequestContext.Principal(1L, "tester@test.com", false, COMPANY_ID, null, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void addRate_duplicatePairDateType_throwsFriendlyConflict() {
        when(rates.existsByCompanyIdAndFromCurrencyAndToCurrencyAndEffectiveDateAndRateType(
                COMPANY_ID, "USD", "TZS", RATE_DATE, "SPOT")).thenReturn(true);

        UpsertRateRequest req = new UpsertRateRequest(
                COMPANY_ID, "USD", "TZS", new BigDecimal("2500.00"), RATE_DATE, "SPOT", "manual");

        assertThatThrownBy(() -> service.addRate(req))
                .isInstanceOf(ConflictException.class)
                .hasMessage("An exchange rate for USD→TZS on 2026-01-15 already exists.");

        verify(rates, never()).save(any(CurrencyRate.class));
    }

    @Test
    void addRate_newPairDateType_savesSuccessfully() {
        when(rates.existsByCompanyIdAndFromCurrencyAndToCurrencyAndEffectiveDateAndRateType(
                COMPANY_ID, "USD", "TZS", RATE_DATE, "SPOT")).thenReturn(false);
        CurrencyRate saved = new CurrencyRate(
                COMPANY_ID, null, "USD", "TZS", new BigDecimal("2500.00"), RATE_DATE, "SPOT", "manual", 1L);
        when(rates.save(any(CurrencyRate.class))).thenReturn(saved);

        UpsertRateRequest req = new UpsertRateRequest(
                COMPANY_ID, "USD", "TZS", new BigDecimal("2500.00"), RATE_DATE, "SPOT", "manual");

        service.addRate(req);

        verify(rates).save(any(CurrencyRate.class));
    }
}
