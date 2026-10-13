package com.erp.modules.ar.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.parties.domain.entity.PaymentTerms;
import com.erp.modules.parties.domain.enums.PaymentTermsBasis;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import org.mockito.ArgumentCaptor;

import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.CurrencyConversionService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;

/**
 * Unit tests for {@link ArOpeningBalanceServiceImpl}:
 *  - Defect fix: null / zero / negative amount must produce a clear IllegalArgumentException (400)
 *    before any GL posting attempt (previously produced a cryptic GL error downstream).
 */
class ArOpeningBalanceServiceImplTest {

    private ArInvoiceRepository       invoices;
    private CustomerRepository        customers;
    private CompanyRepository         companies;
    private GLPostingService          glPosting;
    private GLConfigResolver          glConfig;
    private CurrencyConversionService fxConverter;
    private ScopeGuard                scopeGuard;
    private AuditService              audit;
    private PaymentTermsRepository    paymentTerms;
    private ArOpeningBalanceServiceImpl service;

    private static final Long   COMPANY_ID   = 10L;
    private static final Long   CUSTOMER_ID  = 7L;
    private static final String COMPANY_UID  = "CO-TEST";
    private static final String CUSTOMER_UID = "CUST-TEST";

    @BeforeEach
    void setUp() {
        invoices    = mock(ArInvoiceRepository.class);
        customers   = mock(CustomerRepository.class);
        companies   = mock(CompanyRepository.class);
        glPosting   = mock(GLPostingService.class);
        glConfig    = mock(GLConfigResolver.class);
        fxConverter = mock(CurrencyConversionService.class);
        scopeGuard  = mock(ScopeGuard.class);
        audit       = mock(AuditService.class);

        paymentTerms = mock(PaymentTermsRepository.class);

        service = new ArOpeningBalanceServiceImpl(
                invoices, customers, companies,
                glPosting, glConfig, fxConverter,
                paymentTerms, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(
                1L, "user@test.com", false, COMPANY_ID, 20L, null));

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        when(company.getBaseCurrency()).thenReturn("TZS");
        when(companies.findByUid(COMPANY_UID)).thenReturn(Optional.of(company));
        when(companies.findById(COMPANY_ID)).thenReturn(Optional.of(company));

        when(customers.findByCompanyIdAndUid(COMPANY_ID, CUSTOMER_UID))
                .thenReturn(Optional.of(mock(
                        com.erp.modules.parties.domain.entity.Customer.class,
                        invoc -> CUSTOMER_ID)));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Defect fix: null/zero/negative amount guard
    // -------------------------------------------------------------------------

    @Test
    void setOpeningBalance_nullAmount_throwsIllegalArgument() {
        SetOpeningBalanceRequest req = new SetOpeningBalanceRequest(
                COMPANY_UID, CUSTOMER_UID,
                null,               // <-- null amount
                "TZS",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                "OB-001");

        assertThatThrownBy(() -> service.setOpeningBalance(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount")
                .hasMessageContaining("positive");
    }

    @Test
    void setOpeningBalance_zeroAmount_throwsIllegalArgument() {
        SetOpeningBalanceRequest req = new SetOpeningBalanceRequest(
                COMPANY_UID, CUSTOMER_UID,
                BigDecimal.ZERO,    // <-- zero amount
                "TZS",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                "OB-002");

        assertThatThrownBy(() -> service.setOpeningBalance(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount")
                .hasMessageContaining("positive");
    }

    @Test
    void setOpeningBalance_negativeAmount_throwsIllegalArgument() {
        SetOpeningBalanceRequest req = new SetOpeningBalanceRequest(
                COMPANY_UID, CUSTOMER_UID,
                new BigDecimal("-100.00"),  // <-- negative amount
                "TZS",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                "OB-003");

        assertThatThrownBy(() -> service.setOpeningBalance(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount")
                .hasMessageContaining("positive");
    }

    @Test
    void setOpeningBalance_positiveAmount_succeedsWithoutThrow() {
        // Wire up the full happy path so we can confirm the guard does NOT fire
        ChartOfAccount arAcct     = mock(ChartOfAccount.class);
        ChartOfAccount equityAcct = mock(ChartOfAccount.class);
        when(arAcct.getId()).thenReturn(1200L);
        when(equityAcct.getId()).thenReturn(3000L);
        when(glConfig.resolve(COMPANY_ID, GlConfigKey.ACCOUNTS_RECEIVABLE)).thenReturn(arAcct);
        when(glConfig.resolve(COMPANY_ID, GlConfigKey.OPENING_BALANCE_EQUITY)).thenReturn(equityAcct);

        JournalEntryDto posted = mock(JournalEntryDto.class);
        when(posted.uid()).thenReturn("JE-OB-001");
        when(glPosting.post(any())).thenReturn(posted);

        // FX identity pass-through
        ConvertedAmount conv = ConvertedAmount.identity(new BigDecimal("5000.00"));
        when(fxConverter.toBase(ArgumentMatchers.eq(new BigDecimal("5000.00")),
                ArgumentMatchers.eq("TZS"), ArgumentMatchers.eq(COMPANY_ID), any()))
                .thenReturn(conv);

        ArInvoice savedInvoice = mock(ArInvoice.class);
        when(savedInvoice.getId()).thenReturn(1L);
        when(savedInvoice.getUid()).thenReturn("INV-OB-001");
        when(savedInvoice.getCompanyId()).thenReturn(COMPANY_ID);
        when(savedInvoice.getCustomerId()).thenReturn(CUSTOMER_ID);
        when(savedInvoice.getCurrency())
                .thenReturn(com.erp.platform.common.money.CurrencyCode.of("TZS"));
        when(invoices.save(any())).thenReturn(savedInvoice);

        // Use lenient stubbing for audit (audit.record is void — no stub needed)
        // Build the customer mock BEFORE the when(...) to avoid nested-stubbing (UnfinishedStubbingException).
        com.erp.modules.parties.domain.entity.Customer cust = mockCustomer(CUSTOMER_ID);
        when(customers.findByCompanyIdAndUid(COMPANY_ID, CUSTOMER_UID))
                .thenReturn(Optional.of(cust));

        SetOpeningBalanceRequest req = new SetOpeningBalanceRequest(
                COMPANY_UID, CUSTOMER_UID,
                new BigDecimal("5000.00"),  // positive — guard must not fire
                "TZS",
                LocalDate.of(2026, 1, 1),
                LocalDate.of(2026, 3, 31),
                "OB-004");

        // Must not throw
        service.setOpeningBalance(req);
    }

    // -------------------------------------------------------------------------
    // ARC-15: blank due date follows the customer's terms; ARC-17: branch + base-currency GL
    // -------------------------------------------------------------------------

    @Test
    void blankDueDate_followsCustomerNetDays_andBranchComesFromContext() {
        wireHappyPath(new BigDecimal("1000.00"), "TZS", ConvertedAmount.identity(new BigDecimal("1000.00")));
        com.erp.modules.parties.domain.entity.Customer cust = mockCustomer(CUSTOMER_ID);
        when(cust.getPaymentTermsId()).thenReturn(null);
        when(cust.getPaymentTermsDays()).thenReturn(30);
        when(customers.findByCompanyIdAndUid(COMPANY_ID, CUSTOMER_UID)).thenReturn(Optional.of(cust));

        service.setOpeningBalance(new SetOpeningBalanceRequest(COMPANY_UID, CUSTOMER_UID,
                new BigDecimal("1000.00"), "TZS", LocalDate.of(2026, 1, 1), null, "OB-10"));

        ArgumentCaptor<ArInvoice> inv = ArgumentCaptor.forClass(ArInvoice.class);
        verify(invoices).save(inv.capture());
        assertThat(inv.getValue().getDueDate()).isEqualTo(LocalDate.of(2026, 1, 31));
        assertThat(inv.getValue().getBranchId()).isEqualTo(20L);

        ArgumentCaptor<JournalEntryDraft> je = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glPosting).post(je.capture());
        assertThat(je.getValue().branchId()).isEqualTo(20L);
    }

    @Test
    void blankDueDate_followsThePaymentTermsMaster() {
        wireHappyPath(new BigDecimal("1000.00"), "TZS", ConvertedAmount.identity(new BigDecimal("1000.00")));
        com.erp.modules.parties.domain.entity.Customer cust = mockCustomer(CUSTOMER_ID);
        when(cust.getPaymentTermsId()).thenReturn(55L);
        when(customers.findByCompanyIdAndUid(COMPANY_ID, CUSTOMER_UID)).thenReturn(Optional.of(cust));
        PaymentTerms terms = mock(PaymentTerms.class);
        when(terms.getBasis()).thenReturn(PaymentTermsBasis.DAYS_AFTER_INVOICE);
        when(terms.getNetDays()).thenReturn(14);
        when(paymentTerms.findByCompanyIdAndId(COMPANY_ID, 55L)).thenReturn(Optional.of(terms));

        service.setOpeningBalance(new SetOpeningBalanceRequest(COMPANY_UID, CUSTOMER_UID,
                new BigDecimal("1000.00"), "TZS", LocalDate.of(2026, 1, 1), null, "OB-11"));

        ArgumentCaptor<ArInvoice> inv = ArgumentCaptor.forClass(ArInvoice.class);
        verify(invoices).save(inv.capture());
        assertThat(inv.getValue().getDueDate()).isEqualTo(LocalDate.of(2026, 1, 15));
    }

    @Test
    void foreignCurrencyOpening_postsBaseCurrencyJournalLines() {
        // 100 USD at 2,500 → 250,000 TZS
        wireHappyPath(new BigDecimal("100.00"), "USD", new ConvertedAmount(
                new BigDecimal("250000.00"), new BigDecimal("2500"),
                java.time.Instant.parse("2026-01-01T00:00:00Z")));
        com.erp.modules.parties.domain.entity.Customer cust = mockCustomer(CUSTOMER_ID);
        when(customers.findByCompanyIdAndUid(COMPANY_ID, CUSTOMER_UID)).thenReturn(Optional.of(cust));

        service.setOpeningBalance(new SetOpeningBalanceRequest(COMPANY_UID, CUSTOMER_UID,
                new BigDecimal("100.00"), "USD", LocalDate.of(2026, 1, 1), LocalDate.of(2026, 2, 1),
                "OB-USD"));

        ArgumentCaptor<JournalEntryDraft> je = ArgumentCaptor.forClass(JournalEntryDraft.class);
        verify(glPosting).post(je.capture());
        assertThat(je.getValue().lines()).allSatisfy(l -> assertThat(l.currency()).isEqualTo("TZS"));
        assertThat(je.getValue().lines().get(0).debitAmount()).isEqualByComparingTo("250000.00");
        assertThat(je.getValue().lines().get(1).creditAmount()).isEqualByComparingTo("250000.00");

        ArgumentCaptor<ArInvoice> inv = ArgumentCaptor.forClass(ArInvoice.class);
        verify(invoices).save(inv.capture());
        assertThat(inv.getValue().getOriginalAmount()).isEqualByComparingTo("100.00");
        assertThat(inv.getValue().getBaseOriginalAmount()).isEqualByComparingTo("250000.00");
    }

    private void wireHappyPath(BigDecimal amount, String currency, ConvertedAmount conv) {
        ChartOfAccount arAcct     = mock(ChartOfAccount.class);
        ChartOfAccount equityAcct = mock(ChartOfAccount.class);
        when(arAcct.getId()).thenReturn(1200L);
        when(equityAcct.getId()).thenReturn(3000L);
        when(glConfig.resolve(COMPANY_ID, GlConfigKey.ACCOUNTS_RECEIVABLE)).thenReturn(arAcct);
        when(glConfig.resolve(COMPANY_ID, GlConfigKey.OPENING_BALANCE_EQUITY)).thenReturn(equityAcct);
        JournalEntryDto posted = mock(JournalEntryDto.class);
        when(posted.uid()).thenReturn("JE-OB");
        when(glPosting.post(any())).thenReturn(posted);
        when(fxConverter.toBase(ArgumentMatchers.eq(amount), ArgumentMatchers.eq(currency),
                ArgumentMatchers.eq(COMPANY_ID), any())).thenReturn(conv);
        when(invoices.save(any())).thenAnswer(i -> i.getArgument(0));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static com.erp.modules.parties.domain.entity.Customer mockCustomer(Long id) {
        com.erp.modules.parties.domain.entity.Customer c =
                mock(com.erp.modules.parties.domain.entity.Customer.class);
        when(c.getId()).thenReturn(id);
        return c;
    }
}
