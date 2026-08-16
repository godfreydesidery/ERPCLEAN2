package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.sales.domain.entity.PosTill;
import com.erp.modules.sales.repository.PosTillRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/** Unit test for {@link PosTillProvisioner} — no Docker, no Spring context. */
@ExtendWith(MockitoExtension.class)
class PosTillProvisionerTest {

    @Mock CashBankAccountRepository cashAccounts;
    @Mock PosTillRepository tills;
    @Mock SalesDepthNumberGenerator numberGenerator;

    @InjectMocks PosTillProvisioner provisioner;

    @Test
    @DisplayName("the till is created against the company's default drawer account")
    void createsTheTillAgainstTheDefaultCashAccount() {
        when(cashAccounts.findByCompanyIdAndIsDefaultTrue(9L)).thenReturn(Optional.of(account(500L)));
        when(numberGenerator.nextPosTill(9L)).thenReturn("TILL-0001");

        provisioner.createIfNamed(9L, 3L, "  HQ Till 1  ", 77L);

        ArgumentCaptor<PosTill> captor = ArgumentCaptor.forClass(PosTill.class);
        verify(tills).save(captor.capture());
        PosTill saved = captor.getValue();

        assertThat(saved.getCompanyId()).isEqualTo(9L);
        assertThat(saved.getBranchId()).isEqualTo(3L);
        // Stripped at the boundary and again here: uq_pos_till_company_name is a plain UNIQUE with
        // no btrim, so " HQ Till 1" and "HQ Till 1" would be two different tills forever.
        assertThat(saved.getName()).isEqualTo("HQ Till 1");
        assertThat(saved.getCashBankAccountId()).isEqualTo(500L);
        assertThat(saved.getDefaultPriceListId()).isEqualTo(77L);
        // A blank code reads as a broken field on the open-shift card.
        assertThat(saved.getCode()).isEqualTo("TILL-0001");
    }

    @Test
    @DisplayName("no price list requested leaves the till's default price list unset")
    void createsTheTillWithoutAPriceListWhenNoneWasCreated() {
        when(cashAccounts.findByCompanyIdAndIsDefaultTrue(9L)).thenReturn(Optional.of(account(500L)));

        provisioner.createIfNamed(9L, 3L, "HQ Till 1", null);

        ArgumentCaptor<PosTill> captor = ArgumentCaptor.forClass(PosTill.class);
        verify(tills).save(captor.capture());
        assertThat(captor.getValue().getDefaultPriceListId()).isNull();
    }

    /**
     * Mirrors {@code PosTillServiceImpl.resolveCashAccount}'s fallback, so the provisioning path and
     * the till screen cannot disagree about what "the drawer account" means.
     */
    @Test
    @DisplayName("with no default account, an active one is used")
    void fallsBackToAnyActiveCashAccount() {
        when(cashAccounts.findByCompanyIdAndIsDefaultTrue(9L)).thenReturn(Optional.empty());
        when(cashAccounts.findByCompanyIdAndActive(9L, true)).thenReturn(List.of(account(501L)));

        provisioner.createIfNamed(9L, 3L, "HQ Till 1", null);

        ArgumentCaptor<PosTill> captor = ArgumentCaptor.forClass(PosTill.class);
        verify(tills).save(captor.capture());
        assertThat(captor.getValue().getCashBankAccountId()).isEqualTo(501L);
    }

    /**
     * THE ONE THAT MATTERS. {@code pos_tills.cash_bank_account_id} is NOT NULL behind a foreign key,
     * and {@code CashBankSeeder} legitimately skips a company whose CASH gl_config is missing — so
     * the account can be absent. Inserting anyway would roll back the entire tenant-creation
     * transaction, and the operator would get NO TENANT because an optional convenience could not be
     * created. The absence of the exception is asserted explicitly, because that is the failure being
     * prevented.
     */
    @Test
    @DisplayName("with no cash account at all the till is skipped — never thrown")
    void degradesWhenTheCompanyHasNoCashAccount() {
        when(cashAccounts.findByCompanyIdAndIsDefaultTrue(9L)).thenReturn(Optional.empty());
        when(cashAccounts.findByCompanyIdAndActive(9L, true)).thenReturn(List.of());

        assertThatNoException().isThrownBy(() -> {
            assertThat(provisioner.createIfNamed(9L, 3L, "HQ Till 1", 77L)).isNull();
        });

        verifyNoInteractions(tills, numberGenerator);
    }

    @ParameterizedTest(name = "a blank name [{0}] creates no till and reads no account")
    @NullSource
    @ValueSource(strings = {"", "   "})
    void aBlankNameCreatesNothing(String blank) {
        assertThat(provisioner.createIfNamed(9L, 3L, blank, 77L)).isNull();

        verifyNoInteractions(cashAccounts, tills, numberGenerator);
    }

    private static CashBankAccount account(Long id) {
        CashBankAccount account = new CashBankAccount(9L, 3L, "CASH", "Cash on Hand",
                CashBankAccountType.CASH, "TZS", 100L, true, null);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
