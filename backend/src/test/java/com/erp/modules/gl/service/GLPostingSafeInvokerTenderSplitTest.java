package com.erp.modules.gl.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.cashbank.domain.dto.CashAccountGlResolutionDto;
import com.erp.modules.cashbank.service.CashBankAccountResolver;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.sales.domain.dto.InvoicePostingTenderDto;
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.FxDocumentConverter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * SAL-06 / ACC-04 / ACC-05: the sale entry's debit side is split by tender — each tender to its
 * cash/bank account's own GL (else CASH), only the outstanding to AR — and always balances.
 */
class GLPostingSafeInvokerTenderSplitTest {

    private static final long CO = 10L;
    private static final long CASH = 100L;
    private static final long AR = 110L;
    private static final long MPESA_GL = 120L;
    private static final long REVENUE = 400L;
    private static final long VAT = 300L;

    private GLPostingService postingService;
    private CashBankAccountResolver cashBank;
    private GLPostingSafeInvoker invoker;
    private ArgumentCaptor<JournalEntryDraft> draft;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        postingService = mock(GLPostingService.class);
        GLConfigResolver configResolver = mock(GLConfigResolver.class);
        FxDocumentConverter fx = mock(FxDocumentConverter.class);
        CompanyRepository companies = mock(CompanyRepository.class);
        cashBank = mock(CashBankAccountResolver.class);

        Company co = mock(Company.class);
        when(co.getBaseCurrency()).thenReturn("TZS");
        when(companies.findScopedById(any())).thenReturn(Optional.of(co));
        when(companies.findById(any())).thenReturn(Optional.of(co));
        when(fx.toBase(any(), any(), any(), any()))
                .thenAnswer(inv -> ConvertedAmount.identity(inv.getArgument(0)));
        when(fx.balancingPlug(any(), anyInt()))
                .thenAnswer(inv -> ((List<BigDecimal>) inv.getArgument(0)).stream()
                        .reduce(BigDecimal.ZERO, BigDecimal::add).negate());

        ChartOfAccount cash = account(CASH);
        ChartOfAccount ar = account(AR);
        ChartOfAccount revenue = account(REVENUE);
        ChartOfAccount vat = account(VAT);
        when(configResolver.resolve(CO, GlConfigKey.CASH)).thenReturn(cash);
        when(configResolver.resolve(CO, GlConfigKey.ACCOUNTS_RECEIVABLE)).thenReturn(ar);
        when(configResolver.resolve(CO, GlConfigKey.SALES_REVENUE)).thenReturn(revenue);
        when(configResolver.resolve(CO, GlConfigKey.VAT_PAYABLE)).thenReturn(vat);
        when(cashBank.findSaleTenderGlAccount(CO, 7L))
                .thenReturn(Optional.of(new CashAccountGlResolutionDto(7L, "MPESA", MPESA_GL, "1020")));
        when(cashBank.findSaleTenderGlAccount(CO, 8L)).thenReturn(Optional.empty());

        draft = ArgumentCaptor.forClass(JournalEntryDraft.class);
        when(postingService.post(draft.capture())).thenReturn(mock(JournalEntryDto.class));

        invoker = new GLPostingSafeInvoker(postingService, configResolver, fx, companies, cashBank);
    }

    @Test
    void creditSaleWithCashDeposit_debitsCashForDepositAndArForOutstanding() {
        post(false, "100000", "84746", "15254",
                tender("CASH", null, "40000"));

        assertThat(debit(CASH)).isEqualByComparingTo("40000");
        assertThat(debit(AR)).isEqualByComparingTo("60000");
        assertBalanced();
    }

    @Test
    void creditSaleWithMpesaDeposit_landsOnTheMpesaAccountsGl() {
        post(false, "100000", "84746", "15254",
                tender("MOBILE_MONEY", 7L, "40000"));

        assertThat(debit(MPESA_GL)).isEqualByComparingTo("40000");
        assertThat(debit(AR)).isEqualByComparingTo("60000");
        assertThat(line(CASH)).isNull();
        assertBalanced();
    }

    @Test
    void cashSale_mixedTenders_groupedPerGlAccount_changeNetted() {
        // 9,000 bill: cash 5,000 tendered with 1,000 change, M-Pesa 3,000 + 2,000 on the same wallet
        post(true, "9000", "7627", "1373",
                tender("CASH", null, "4000"),
                tender("MOBILE_MONEY", 7L, "3000"),
                tender("MOBILE_MONEY", 7L, "2000"));

        assertThat(debit(CASH)).isEqualByComparingTo("4000");
        assertThat(debit(MPESA_GL)).isEqualByComparingTo("5000");
        assertThat(draft.getValue().lines()).hasSize(4); // CASH, M-Pesa, revenue, VAT
        assertBalanced();
    }

    @Test
    void tenderOnAnUnusableAccount_fallsBackToCash() {
        post(true, "1180", "1000", "180", tender("CARD", 8L, "1180"));

        assertThat(debit(CASH)).isEqualByComparingTo("1180");
        assertBalanced();
    }

    @Test
    void noTenders_creditSale_isTheOldSingleArLeg() {
        post(false, "1180", "1000", "180");

        assertThat(debit(AR)).isEqualByComparingTo("1180");
        assertThat(line(AR).lineMemo()).isEqualTo("Gross sale");
        assertThat(draft.getValue().lines()).hasSize(3);
        assertBalanced();
    }

    @Test
    void plainCashSale_isTheOldSingleCashLeg() {
        post(true, "1180", "1000", "180", tender("CASH", null, "1180"));

        assertThat(debit(CASH)).isEqualByComparingTo("1180");
        assertThat(line(CASH).lineMemo()).isEqualTo("Gross sale");
        assertThat(draft.getValue().lines()).hasSize(3);
        assertBalanced();
    }

    // -------------------------------------------------------------------------

    private void post(boolean cashSale, String gross, String net, String vat,
                      InvoicePostingTenderDto... tenders) {
        invoker.postSaleWithTendersInNewTx(CO, 20L, "inv-1", "TZS", new BigDecimal(gross),
                new BigDecimal(net), new BigDecimal(vat), cashSale, List.of(tenders),
                LocalDate.of(2026, 10, 10), null, null, null, null);
    }

    private static InvoicePostingTenderDto tender(String type, Long accountId, String net) {
        return new InvoicePostingTenderDto(type, accountId, new BigDecimal(net));
    }

    private LineDraft line(long accountId) {
        return draft.getValue().lines().stream()
                .filter(l -> l.accountId().equals(accountId))
                .findFirst().orElse(null);
    }

    private BigDecimal debit(long accountId) {
        LineDraft l = line(accountId);
        assertThat(l).as("line for account " + accountId).isNotNull();
        return l.debitAmount();
    }

    private void assertBalanced() {
        BigDecimal dr = BigDecimal.ZERO;
        BigDecimal cr = BigDecimal.ZERO;
        for (LineDraft l : draft.getValue().lines()) {
            dr = dr.add(l.debitAmount());
            cr = cr.add(l.creditAmount());
            assertThat(l.debitAmount().signum() == 0 || l.creditAmount().signum() == 0).isTrue();
        }
        assertThat(dr).isEqualByComparingTo(cr);
    }

    private static ChartOfAccount account(long id) {
        ChartOfAccount a = mock(ChartOfAccount.class);
        when(a.getId()).thenReturn(id);
        return a;
    }
}
