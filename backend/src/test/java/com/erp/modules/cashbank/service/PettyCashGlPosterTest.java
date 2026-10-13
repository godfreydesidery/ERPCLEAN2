package com.erp.modules.cashbank.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.PettyCashFund;
import com.erp.modules.cashbank.domain.enums.PettyCashTxnType;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.ControlType;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.common.api.AccountingSetupException;
import com.erp.platform.common.money.CurrencyConversionService;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * Which GL account petty cash posts to. It must never be an account a till or bank already posts
 * to (ARC-01 gave tills their own GL account, often 1010): that would break the till's cash-book /
 * GL tie-out.
 */
class PettyCashGlPosterTest {

    private static final Long COMPANY_ID = 1L;

    private ChartOfAccountRepository glAccounts;
    private CashBankAccountRepository cashAccounts;
    private PettyCashGlPoster poster;
    private PettyCashFund fund;

    @BeforeEach
    void setUp() {
        glAccounts   = mock(ChartOfAccountRepository.class);
        cashAccounts = mock(CashBankAccountRepository.class);
        GLConfigResolver glConfig = mock(GLConfigResolver.class);
        ChartOfAccount cashShort = account(5170L, "5170", "Cash Short", AccountType.EXPENSE);
        when(glConfig.resolve(COMPANY_ID, GlConfigKey.POS_CASH_SHORT)).thenReturn(cashShort);
        when(glAccounts.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(glAccounts.findByCompanyIdAndAccountTypeIn(eq(COMPANY_ID), any())).thenReturn(List.of());
        when(glAccounts.findByCompanyIdAndAccountCode(eq(COMPANY_ID), anyString()))
                .thenReturn(Optional.empty());
        when(cashAccounts.findByCompanyIdAndGlAccountId(eq(COMPANY_ID), anyLong()))
                .thenReturn(Optional.empty());

        poster = new PettyCashGlPoster(glAccounts, cashAccounts, mock(CashBankAccountResolver.class),
                mock(CashTransactionRecorder.class), glConfig, mock(GLPostingService.class),
                mock(CompanyRepository.class), mock(CurrencyConversionService.class));
        fund = mock(PettyCashFund.class);
        when(fund.getCompanyId()).thenReturn(COMPANY_ID);
    }

    /** Plans a shortage adjustment — the cheapest movement that resolves the petty-cash account. */
    private PettyCashGlPoster.Plan plan() {
        return poster.plan(fund, PettyCashTxnType.ADJUSTMENT, new BigDecimal("-10"), null, null);
    }

    @Test
    void code1010IsALinkedTillAccount_pettyCashGoesToANew1011Account() {
        ChartOfAccount till = account(1010L, "1010", "Front Till", AccountType.ASSET);
        when(glAccounts.findByCompanyIdAndAccountCode(COMPANY_ID, "1010")).thenReturn(Optional.of(till));
        when(glAccounts.existsByCompanyIdAndAccountCode(COMPANY_ID, "1010")).thenReturn(true);
        when(cashAccounts.findByCompanyIdAndGlAccountId(COMPANY_ID, 1010L))
                .thenReturn(Optional.of(mock(CashBankAccount.class)));

        plan();

        ArgumentCaptor<ChartOfAccount> created = ArgumentCaptor.forClass(ChartOfAccount.class);
        verify(glAccounts).save(created.capture());
        assertThat(created.getValue().getAccountCode()).isEqualTo("1011");
        assertThat(created.getValue().getName()).isEqualTo("Petty Cash");
        assertThat(created.getValue().getAccountType()).isEqualTo(AccountType.ASSET);
        assertThat(created.getValue().getControlType()).isEqualTo(ControlType.CASH);
    }

    @Test
    void code1010IsAPettyCashNamedTillAccount_isStillNotUsed() {
        ChartOfAccount linked = account(1010L, "1010", "Petty Cash Till", AccountType.ASSET);
        when(glAccounts.findByCompanyIdAndAccountCode(COMPANY_ID, "1010")).thenReturn(Optional.of(linked));
        when(glAccounts.existsByCompanyIdAndAccountCode(COMPANY_ID, "1010")).thenReturn(true);
        when(cashAccounts.findByCompanyIdAndGlAccountId(COMPANY_ID, 1010L))
                .thenReturn(Optional.of(mock(CashBankAccount.class)));

        plan();

        ArgumentCaptor<ChartOfAccount> created = ArgumentCaptor.forClass(ChartOfAccount.class);
        verify(glAccounts).save(created.capture());
        assertThat(created.getValue().getAccountCode()).isEqualTo("1011");
    }

    @Test
    void existingPettyCashAccountOnAnotherCode_isReused() {
        ChartOfAccount existing = account(1055L, "1055", "petty cash ", AccountType.ASSET);
        ChartOfAccount cash = account(1000L, "1000", "Cash", AccountType.ASSET);
        when(glAccounts.findByCompanyIdAndAccountTypeIn(eq(COMPANY_ID), any()))
                .thenReturn(List.of(cash, existing));

        assertThat(plan().pettyCashGlAccountId()).isEqualTo(1055L);
        verify(glAccounts, never()).save(any());
    }

    @Test
    void unlinkedPettyCashLike1010_isUsed() {
        ChartOfAccount at1010 = account(1010L, "1010", "Petty cash float", AccountType.ASSET);
        when(glAccounts.findByCompanyIdAndAccountCode(COMPANY_ID, "1010")).thenReturn(Optional.of(at1010));

        assertThat(plan().pettyCashGlAccountId()).isEqualTo(1010L);
        verify(glAccounts, never()).save(any());
    }

    @Test
    void noAccountAtAll_createsPettyCashOn1010() {
        plan();

        ArgumentCaptor<ChartOfAccount> created = ArgumentCaptor.forClass(ChartOfAccount.class);
        verify(glAccounts).save(created.capture());
        assertThat(created.getValue().getAccountCode()).isEqualTo("1010");
    }

    @Test
    void codes1010To1019AllTaken_refusedWithSetupMessage() {
        ChartOfAccount till = account(1010L, "1010", "Front Till", AccountType.ASSET);
        when(glAccounts.findByCompanyIdAndAccountCode(COMPANY_ID, "1010")).thenReturn(Optional.of(till));
        when(glAccounts.existsByCompanyIdAndAccountCode(eq(COMPANY_ID), anyString())).thenReturn(true);

        assertThatThrownBy(this::plan)
                .isInstanceOf(AccountingSetupException.class)
                .hasMessageContaining("Petty Cash");
        verify(glAccounts, never()).save(any());
    }

    private static ChartOfAccount account(Long id, String code, String name, AccountType type) {
        ChartOfAccount a = mock(ChartOfAccount.class);
        when(a.getId()).thenReturn(id);
        when(a.getCompanyId()).thenReturn(COMPANY_ID);
        when(a.getAccountCode()).thenReturn(code);
        when(a.getName()).thenReturn(name);
        when(a.getAccountType()).thenReturn(type);
        when(a.isActive()).thenReturn(true);
        return a;
    }
}
