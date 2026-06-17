package com.erp.modules.fx.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

import com.erp.modules.fx.domain.dto.CompanyCurrencyDto;
import com.erp.modules.fx.domain.dto.EnableCurrencyRequest;
import com.erp.modules.fx.domain.entity.CompanyCurrency;
import com.erp.modules.fx.domain.entity.Currency;
import com.erp.modules.fx.domain.exception.CurrencyNotEnabledException;
import com.erp.modules.fx.repository.BranchCurrencyRepository;
import com.erp.modules.fx.repository.CompanyCurrencyRepository;
import com.erp.modules.fx.repository.CurrencyRepository;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.money.CurrencyCode;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Unit tests for {@link CurrencyEnablementServiceImpl} business invariants (ADR-0039 D-7/D-8).
 *
 * <p>Covers:
 * <ul>
 *   <li>enabledForCompany returns active rows only.
 *   <li>resolveDefault: branch default → company default → company base fallback.
 *   <li>assertEnabled: throws {@link CurrencyNotEnabledException} when not in allow-list.
 *   <li>deactivateForCompany: base currency CANNOT be deactivated (invariant).
 *   <li>deactivateForCompany: default row CANNOT be deactivated (invariant).
 *   <li>setDefaultForCompany: shifts the is_default flag to the new row.
 *   <li>enableForCompany: idempotent re-activation of an existing row.
 *   <li>enableForCompany: rejects a globally-inactive currency.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class CurrencyEnablementServiceImplTest {

    @Mock CompanyCurrencyRepository companyCurrencies;
    @Mock BranchCurrencyRepository  branchCurrencies;
    @Mock CurrencyRepository        currencies;
    @Mock CompanyRepository         companies;

    @InjectMocks CurrencyEnablementServiceImpl service;

    private static final Long    COMPANY_ID  = 1L;
    private static final Long    BRANCH_ID   = 10L;
    private static final CurrencyCode TZS    = CurrencyCode.of("TZS");
    private static final CurrencyCode USD    = CurrencyCode.of("USD");
    private static final CurrencyCode KES    = CurrencyCode.of("KES");

    private Company company;

    @BeforeEach
    void setUp() {
        Organisation org = new Organisation("Test Org");
        company = new Company(org, "C1", "Test Co");
        // base currency is TZS by default
    }

    // ── enabledForCompany ─────────────────────────────────────────────────────

    @Test
    void enabledForCompany_returnsActiveCurrencies() {
        CompanyCurrency tzs = ccRow(COMPANY_ID, TZS, true, true);
        CompanyCurrency usd = ccRow(COMPANY_ID, USD, false, true);
        when(companyCurrencies.findByCompanyIdAndActiveTrueOrderByCurrencyCode(COMPANY_ID))
                .thenReturn(List.of(tzs, usd));

        List<CompanyCurrencyDto> result = service.enabledForCompany(COMPANY_ID);

        assertThat(result).hasSize(2);
        assertThat(result.stream().map(CompanyCurrencyDto::currencyCode))
                .containsExactly("TZS", "USD");
    }

    // ── resolveDefault ────────────────────────────────────────────────────────

    @Test
    void resolveDefault_returnsBranchDefaultFirst() {
        when(branchCurrencies.findByBranchIdAndIsDefaultTrue(BRANCH_ID))
                .thenReturn(Optional.of(asBranchCurrency(COMPANY_ID, BRANCH_ID, USD, true)));

        CurrencyCode result = service.resolveDefault(COMPANY_ID, BRANCH_ID);

        assertThat(result).isEqualTo(USD);
    }

    @Test
    void resolveDefault_fallsBackToCompanyDefault_whenNoBranchDefault() {
        when(branchCurrencies.findByBranchIdAndIsDefaultTrue(BRANCH_ID))
                .thenReturn(Optional.empty());
        CompanyCurrency companyDefault = ccRow(COMPANY_ID, KES, true, true);
        when(companyCurrencies.findByCompanyIdAndIsDefaultTrue(COMPANY_ID))
                .thenReturn(Optional.of(companyDefault));

        CurrencyCode result = service.resolveDefault(COMPANY_ID, BRANCH_ID);

        assertThat(result).isEqualTo(KES);
    }

    @Test
    void resolveDefault_fallsBackToCompanyBase_whenNoDefaultRows() {
        when(branchCurrencies.findByBranchIdAndIsDefaultTrue(BRANCH_ID))
                .thenReturn(Optional.empty());
        when(companyCurrencies.findByCompanyIdAndIsDefaultTrue(COMPANY_ID))
                .thenReturn(Optional.empty());
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        CurrencyCode result = service.resolveDefault(COMPANY_ID, BRANCH_ID);

        assertThat(result).isEqualTo(TZS); // company.baseCurrency defaults to "TZS"
    }

    // ── assertEnabled ─────────────────────────────────────────────────────────

    @Test
    void assertEnabled_passes_whenCurrencyIsActiveInCompanyList() {
        when(branchCurrencies.countByBranchId(BRANCH_ID)).thenReturn(0);
        when(companyCurrencies.findByCompanyIdAndCurrencyCodeAndActiveTrue(COMPANY_ID, USD))
                .thenReturn(Optional.of(ccRow(COMPANY_ID, USD, false, true)));

        // Should not throw
        service.assertEnabled(COMPANY_ID, BRANCH_ID, USD);
    }

    @Test
    void assertEnabled_throws_whenCurrencyNotInCompanyList() {
        when(branchCurrencies.countByBranchId(BRANCH_ID)).thenReturn(0);
        when(companyCurrencies.findByCompanyIdAndCurrencyCodeAndActiveTrue(COMPANY_ID, USD))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertEnabled(COMPANY_ID, BRANCH_ID, USD))
                .isInstanceOf(CurrencyNotEnabledException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("not enabled for company");
    }

    @Test
    void assertEnabled_throwsWithBranchScope_whenBranchHasOwnListAndCodeMissing() {
        when(branchCurrencies.countByBranchId(BRANCH_ID)).thenReturn(1); // branch has own list
        when(branchCurrencies.findByBranchIdAndCurrencyCodeAndActiveTrue(BRANCH_ID, USD))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.assertEnabled(COMPANY_ID, BRANCH_ID, USD))
                .isInstanceOf(CurrencyNotEnabledException.class)
                .hasMessageContaining("branch " + BRANCH_ID);
    }

    // ── deactivateForCompany — base currency cannot be deactivated ────────────

    @Test
    void deactivateForCompany_throwsConflict_whenDeactivatingBaseCurrency() {
        CompanyCurrency tzsRow = ccRow(COMPANY_ID, TZS, false, true);
        when(companyCurrencies.findByCompanyIdAndCurrencyCode(COMPANY_ID, TZS))
                .thenReturn(Optional.of(tzsRow));
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company)); // base = TZS

        assertThatThrownBy(() -> service.deactivateForCompany(COMPANY_ID, "TZS"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot deactivate the company base currency");
    }

    @Test
    void deactivateForCompany_throwsConflict_whenDeactivatingDefaultRow() {
        CompanyCurrency usdRow = ccRow(COMPANY_ID, USD, true /* isDefault */, true);
        when(companyCurrencies.findByCompanyIdAndCurrencyCode(COMPANY_ID, USD))
                .thenReturn(Optional.of(usdRow));
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company)); // base = TZS ≠ USD

        assertThatThrownBy(() -> service.deactivateForCompany(COMPANY_ID, "USD"))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Cannot deactivate the default document currency");
    }

    @Test
    void deactivateForCompany_succeeds_forNonBaseNonDefaultRow() {
        CompanyCurrency kesRow = ccRow(COMPANY_ID, KES, false, true);
        when(companyCurrencies.findByCompanyIdAndCurrencyCode(COMPANY_ID, KES))
                .thenReturn(Optional.of(kesRow));
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        CompanyCurrencyDto result = service.deactivateForCompany(COMPANY_ID, "KES");

        assertThat(result.active()).isFalse();
    }

    // ── setDefaultForCompany — shifts the flag ────────────────────────────────

    @Test
    void setDefaultForCompany_shiftsDefaultFlag() {
        CompanyCurrency oldDefault = ccRow(COMPANY_ID, TZS, true, true);
        CompanyCurrency newDefault = ccRow(COMPANY_ID, USD, false, true);
        when(companyCurrencies.findByCompanyIdAndCurrencyCode(COMPANY_ID, USD))
                .thenReturn(Optional.of(newDefault));
        when(companyCurrencies.findByCompanyIdAndIsDefaultTrue(COMPANY_ID))
                .thenReturn(Optional.of(oldDefault));

        CompanyCurrencyDto result = service.setDefaultForCompany(COMPANY_ID, "USD");

        assertThat(result.isDefault()).isTrue();
        assertThat(oldDefault.isDefault()).isFalse(); // old cleared
    }

    // ── enableForCompany — idempotent re-activation ───────────────────────────

    @Test
    void enableForCompany_reactivatesInactiveRow() {
        CompanyCurrency inactive = ccRow(COMPANY_ID, USD, false, false);
        Currency globalUsd = activeCurrency("USD");
        when(currencies.findByCode("USD")).thenReturn(Optional.of(globalUsd));
        when(companyCurrencies.findByCompanyIdAndCurrencyCode(COMPANY_ID, USD))
                .thenReturn(Optional.of(inactive));

        CompanyCurrencyDto result = service.enableForCompany(
                COMPANY_ID, new EnableCurrencyRequest("USD", false));

        assertThat(result.active()).isTrue();
    }

    @Test
    void enableForCompany_rejectsGloballyInactiveCurrency() {
        Currency inactiveGlobal = new Currency("EUR", "Euro", "€", (short) 2, null);
        inactiveGlobal.setActive(false);
        when(currencies.findByCode("EUR")).thenReturn(Optional.of(inactiveGlobal));
        var req = new EnableCurrencyRequest("EUR", false);

        assertThatThrownBy(() -> service.enableForCompany(COMPANY_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not globally active");
    }

    // ── Branch subset rule ────────────────────────────────────────────────────

    @Test
    void enableForBranch_rejectsCodeNotEnabledAtCompany() {
        when(companyCurrencies.findByCompanyIdAndCurrencyCodeAndActiveTrue(COMPANY_ID, USD))
                .thenReturn(Optional.empty()); // not enabled at company level
        var req = new EnableCurrencyRequest("USD", false);

        assertThatThrownBy(() -> service.enableForBranch(COMPANY_ID, BRANCH_ID, req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Enable it at the company level first");
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private static CompanyCurrency ccRow(Long companyId, CurrencyCode code,
                                          boolean isDefault, boolean active) {
        return new CompanyCurrency(companyId, code, isDefault, active, null);
    }

    private static com.erp.modules.fx.domain.entity.BranchCurrency asBranchCurrency(
            Long companyId, Long branchId, CurrencyCode code, boolean isDefault) {
        return new com.erp.modules.fx.domain.entity.BranchCurrency(
                companyId, branchId, code, isDefault, true, null);
    }

    private static Currency activeCurrency(String code) {
        return new Currency(code, code + " name", null, (short) 2, null);
    }
}
