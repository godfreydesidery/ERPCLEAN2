package com.erp.modules.cashbank.service;

import static com.erp.support.TenantFixtures.inOrganisation;
import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.entity.CashBankAccount;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.repository.CashBankAccountRepository;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.gl.domain.dto.CreateAccountRequest;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.AccountType;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.CreateCustomerRequest;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.CustomerKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.parties.service.CustomerService;
import com.erp.modules.products.domain.dto.CreatePriceListRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.sales.domain.dto.AddInvoiceLineRequest;
import com.erp.modules.sales.domain.dto.AddPaymentRequest;
import com.erp.modules.sales.domain.dto.CloseSessionRequest;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.CreateSalesInvoiceRequest;
import com.erp.modules.sales.domain.dto.FinaliseInvoiceRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PosPayoutDto;
import com.erp.modules.sales.domain.dto.PosPayoutRequest;
import com.erp.modules.sales.domain.dto.PosSessionDto;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.modules.sales.domain.dto.ReconcileSessionRequest;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.UpdateSalesSettingsRequest;
import com.erp.modules.sales.domain.dto.VoidInvoiceRequest;
import com.erp.modules.sales.domain.enums.PosPayoutType;
import com.erp.modules.sales.domain.enums.TenderType;
import com.erp.modules.sales.service.PosSessionService;
import com.erp.modules.sales.service.PosTillService;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.modules.sales.service.SalesSettingsService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventStatus;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Sales, voids, POS payouts and POS over/short reach the cash book (gap review wave 3, ARC-08),
 * through the real services and the real outbox dispatch. Each cash row mirrors a journal the
 * flow already posted: same account (via the cash/bank account's GL link), amount, date and
 * journal uid — never a second GL entry.
 */
class SalesCashBookIT extends PostgresIntegrationTest {

    static final BigDecimal GROSS = new BigDecimal("1180");

    @Autowired SalesInvoiceService salesInvoiceService;
    @Autowired TaxRateSeeder taxRateSeeder;
    @Autowired SalesSettingsService salesSettingsService;
    @Autowired CustomerService customerService;
    @Autowired AgentService agentService;
    @Autowired ProductService productService;
    @Autowired PriceListService priceListService;
    @Autowired UnitOfMeasureService unitService;
    @Autowired DomainEventRepository domainEventRepository;
    @Autowired DomainEventDispatcher dispatcher;
    @Autowired JournalEntryRepository journalEntries;
    @Autowired ChartOfAccountRepository glAccounts;
    @Autowired ChartOfAccountService chartOfAccountService;
    @Autowired FiscalCalendarService fiscalCalendarService;
    @Autowired GlConfigService glConfigService;
    @Autowired CashBankAccountService cashBankAccountService;
    @Autowired CashBankAccountRepository cashAccounts;
    @Autowired CashTransactionRepository cashTxns;
    @Autowired CashBankSeeder cashBankSeeder;
    @Autowired CashBookJournalMirror mirror;
    @Autowired CashCountService cashCountService;
    @Autowired CashDirectEntryService directEntries;
    @Autowired com.erp.modules.gl.repository.JournalLineRepository journalLines;
    @Autowired PosTillService tillService;
    @Autowired PosSessionService sessionService;
    @Autowired OrganisationRepository organisations;
    @Autowired CompanyRepository companies;
    @Autowired BranchRepository branches;
    @Autowired AppUserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired IamTestData testData;
    @Autowired com.erp.platform.common.time.CompanyCalendar calendar;

    Company company;
    Branch branch;
    String cashCustomerUid;
    String agentUid;
    String pcsUid;
    String productUid;
    CashBankAccount cashAccount;
    Long mpesaAccountId;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Cash Book IT Org"));
        company = companies.save(new Company(org, "CBKIT", "Cash Book IT Co"));
        branch = branches.save(new Branch(company, "CBKIT1", "Cash Book IT Branch"));

        AppUser root = new AppUser("cbk_root", passwordEncoder.encode("RootPass1!"), "CBK Root");
        root.setRoot(true);
        root = users.save(inOrganisation(root, org.getId()));
        RequestContext.set(new RequestContext.Principal(
                root.getId(), "cbk_root", true, company.getId(), branch.getId(), null));

        taxRateSeeder.seedDefaults(company.getId());
        salesSettingsService.update(new UpdateSalesSettingsRequest(
                company.getUid(), false, null, "TZS", true));

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        String priceListUid = priceListService.create(
                new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();
        productUid = productService.create(new CreateProductRequest(
                company.getUid(), null, "CBK Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null)).uid();
        productService.setPrice(productUid,
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));

        cashCustomerUid = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Walk-in",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).uid();
        agentUid = agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "CBK Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.EXTERNAL, null)).uid();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        // The default cash account, linked to the GL CASH mapping cash sales post to.
        cashBankSeeder.seedDefaults(company.getId());
        cashAccount = cashAccounts.findByCompanyIdAndIsDefaultTrue(company.getId()).orElseThrow();

        String mpesaGlUid = chartOfAccountService.create(new CreateAccountRequest(
                company.getUid(), "1099", "M-Pesa Wallet", AccountType.ASSET)).uid();
        mpesaAccountId = cashBankAccountService.create(new CreateCashBankAccountRequest(
                company.getUid(), null, "M-Pesa Till", CashBankAccountType.BANK,
                "Vodacom M-Pesa", "555123", null, mpesaGlUid, false)).id();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void splitCashSale_writesOneSaleTenderRowPerAccount_mirroringTheSaleJournal_once() {
        SalesInvoiceDto inv = cashSale(
                new AddPaymentRequest(TenderType.CASH, new BigDecimal("680"), "TZS", null),
                new AddPaymentRequest(TenderType.MOBILE_MONEY, new BigDecimal("500"), "TZS",
                        "MP-1", mpesaAccountId, null, "QWE123", null));

        JournalEntry sale = journal(JournalSourceType.SALES, inv.uid());
        List<CashTransaction> rows = rows(inv.uid(), CashTxnType.SALE_TENDER);
        assertThat(rows).hasSize(2);
        assertThat(rows).allSatisfy(r -> {
            assertThat(r.getDirection()).isEqualTo(CashTxnDirection.IN);
            assertThat(r.getJournalEntryRef()).isEqualTo(sale.getUid());
            assertThat(r.getTxnDate()).isEqualTo(sale.getPostingDate());
        });
        assertThat(amountOn(rows, cashAccount.getId())).isEqualByComparingTo("680");
        assertThat(amountOn(rows, mpesaAccountId)).isEqualByComparingTo("500");

        // Redelivery / a second attempt never doubles the cash book.
        assertThat(mirror.mirrorSale(company.getId(), branch.getId(), inv.uid(), null)).isZero();
        assertThat(rows(inv.uid(), CashTxnType.SALE_TENDER)).hasSize(2);
    }

    @Test
    void voidedSale_writesSaleRefundRows_linkedToTheTenders_cashBookBackToZero() {
        SalesInvoiceDto inv = cashSale(
                new AddPaymentRequest(TenderType.CASH, GROSS, "TZS", null));
        assertThat(bookBalance(cashAccount.getId())).isEqualByComparingTo(GROSS);

        voidAndDispatch(inv.uid());

        JournalEntry reversal = journal(JournalSourceType.SALES_REVERSAL, inv.uid());
        CashTransaction tender = rows(inv.uid(), CashTxnType.SALE_TENDER).get(0);
        List<CashTransaction> refunds = rows(inv.uid(), CashTxnType.SALE_REFUND);
        assertThat(refunds).singleElement().satisfies(r -> {
            assertThat(r.getDirection()).isEqualTo(CashTxnDirection.OUT);
            assertThat(r.getAmount()).isEqualByComparingTo(GROSS);
            assertThat(r.getCashBankAccountId()).isEqualTo(cashAccount.getId());
            assertThat(r.getJournalEntryRef()).isEqualTo(reversal.getUid());
            assertThat(r.getReversalOfTransactionId()).isEqualTo(tender.getId());
        });
        assertThat(bookBalance(cashAccount.getId())).isEqualByComparingTo("0");
        assertThat(mirror.mirrorSaleVoid(company.getId(), branch.getId(), inv.uid(), null)).isZero();
    }

    @Test
    void posPayoutAndSessionOver_writePosRows_onTheCashAccount_mirroringTheirJournals() {
        PosSessionDto session = openSession(new BigDecimal("500"));
        PosPayoutDto payout = sessionService.recordPayout(session.uid(), new PosPayoutRequest(
                PosPayoutType.PAID_OUT, new BigDecimal("100"), "Delivery tip"));
        dispatchPending(DomainEventType.POS_CASH_MOVED);

        assertThat(rows(payout.uid(), CashTxnType.POS_PAYOUT)).singleElement().satisfies(r -> {
            assertThat(r.getDirection()).isEqualTo(CashTxnDirection.OUT);
            assertThat(r.getAmount()).isEqualByComparingTo("100");
            assertThat(r.getCashBankAccountId()).isEqualTo(cashAccount.getId());
            assertThat(r.getJournalEntryRef()).isEqualTo(payout.journalEntryUid());
        });

        // expected = 500 float − 100 payout = 400; counted 450 → 50 over.
        sessionService.closeSession(session.uid(),
                new CloseSessionRequest(new BigDecimal("450"), null));
        sessionService.reconcileSession(session.uid(), new ReconcileSessionRequest(null));
        dispatchPending(DomainEventType.POS_CASH_MOVED);

        JournalEntry variance = journal(JournalSourceType.POS_VARIANCE, session.uid());
        assertThat(rows(session.uid(), CashTxnType.POS_VARIANCE)).singleElement().satisfies(r -> {
            assertThat(r.getDirection()).isEqualTo(CashTxnDirection.IN);
            assertThat(r.getAmount()).isEqualByComparingTo("50");
            assertThat(r.getJournalEntryRef()).isEqualTo(variance.getUid());
        });
    }

    // ── ARC-01 lifted: the sales till can be counted again ───────────────────────

    @Test
    void cashCount_onTheSalesTill_dayWithSalesPayoutAndRefund_expectedIsTheDrawer_noCashOver() {
        java.time.LocalDate today = calendar.today(company.getId());
        directEntries.recordDirectEntry(new com.erp.modules.cashbank.domain.dto.RecordDirectEntryRequest(
                company.getUid(), cashAccount.getUid(), CashTxnDirection.IN,
                new BigDecimal("1000"), today, glUid("3000"), "Opening float"));
        cashSale(new AddPaymentRequest(TenderType.CASH, GROSS, "TZS", null));
        SalesInvoiceDto refunded = cashSale(new AddPaymentRequest(TenderType.CASH, GROSS, "TZS", null));
        voidAndDispatch(refunded.uid());
        PosSessionDto session = openSession(BigDecimal.ZERO);
        sessionService.recordPayout(session.uid(), new PosPayoutRequest(
                PosPayoutType.PAID_OUT, new BigDecimal("100"), "Delivery tip"));
        dispatchPending(DomainEventType.POS_CASH_MOVED);

        // The business day the rows carry (journal posting dates; all the same day here).
        java.time.LocalDate day = cashTxns.findByCashBankAccountIdOrderByTxnDateAscIdAsc(
                cashAccount.getId()).stream().map(CashTransaction::getTxnDate)
                .max(java.time.LocalDate::compareTo).orElseThrow();
        BigDecimal drawer = new BigDecimal("2080"); // 1000 float + 1180 + 1180 − 1180 − 100

        var count = cashCountService.open(new com.erp.modules.cashbank.domain.dto.OpenCashCountRequest(
                company.getUid(), cashAccount.getUid(), day));
        assertThat(count.expectedAmount()).isEqualByComparingTo(drawer);

        cashCountService.recordDenominations(count.uid(),
                new com.erp.modules.cashbank.domain.dto.RecordDenominationsRequest(List.of(
                        new com.erp.modules.cashbank.domain.dto.RecordDenominationsRequest.Line(
                                new BigDecimal("1000"), 2),
                        new com.erp.modules.cashbank.domain.dto.RecordDenominationsRequest.Line(
                                new BigDecimal("80"), 1))));
        var reconciled = cashCountService.reconcile(count.uid());

        assertThat(reconciled.varianceAmount()).isEqualByComparingTo("0");
        assertThat(reconciled.journalEntryRef()).isNull();
        assertThat(journalEntries.findByCompanyIdAndSourceTypeAndSourceRef(
                company.getId(), JournalSourceType.POS_VARIANCE, count.uid()))
                .as("no cash-over income for counting exactly the drawer").isEmpty();
        // The cash book and the GL cash account agree.
        assertThat(journalLines.accountBalance(company.getId(), cashAccount.getGlAccountId()))
                .isEqualByComparingTo(drawer);
        assertThat(bookBalance(cashAccount.getId())).isEqualByComparingTo(drawer);
    }

    // -------------------------------------------------------------------------

    String glUid(String code) {
        return glAccounts.findByCompanyIdAndAccountCode(company.getId(), code).orElseThrow().getUid();
    }

    SalesInvoiceDto cashSale(AddPaymentRequest... payments) {
        SalesInvoiceDto draft = salesInvoiceService.create(new CreateSalesInvoiceRequest(
                company.getUid(), cashCustomerUid, agentUid, "TZS", null, null));
        salesInvoiceService.addLine(draft.uid(),
                new AddInvoiceLineRequest(productUid, pcsUid, BigDecimal.ONE, null, null));
        for (AddPaymentRequest p : payments) {
            salesInvoiceService.addPayment(draft.uid(), p);
        }
        salesInvoiceService.finalise(draft.uid(), new FinaliseInvoiceRequest());
        dispatcher.dispatchOne(event(DomainEventType.SALE_FINALISED, draft.uid()).getId());
        SalesInvoiceDto inv = salesInvoiceService.getByUid(draft.uid());
        assertThat(inv.grossTotalAmount()).isEqualByComparingTo(GROSS);
        return inv;
    }

    void voidAndDispatch(String invoiceUid) {
        salesInvoiceService.voidInvoice(invoiceUid, new VoidInvoiceRequest("Wrong item", null));
        dispatcher.dispatchOne(event(DomainEventType.SALE_VOIDED, invoiceUid).getId());
    }

    PosSessionDto openSession(BigDecimal openingFloat) {
        PosTillDto till = tillService.createTill(new CreatePosTillRequest(
                company.getUid(), branch.getId(), "Till 1", cashAccount.getUid()));
        return sessionService.openSession(new OpenSessionRequest(till.uid(), openingFloat));
    }

    void dispatchPending(String type) {
        domainEventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType())
                        && e.getStatus() == DomainEventStatus.PENDING)
                .forEach(e -> dispatcher.dispatchOne(e.getId()));
    }

    DomainEvent event(String type, String aggregateUid) {
        return domainEventRepository.findAll().stream()
                .filter(e -> type.equals(e.getEventType()) && aggregateUid.equals(e.getAggregateUid()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No " + type + " event for " + aggregateUid));
    }

    JournalEntry journal(JournalSourceType type, String sourceRef) {
        return journalEntries.findByCompanyIdAndSourceTypeAndSourceRef(company.getId(), type, sourceRef)
                .orElseThrow(() -> new AssertionError("No " + type + " journal for " + sourceRef));
    }

    List<CashTransaction> rows(String sourceRef, CashTxnType type) {
        return cashTxns.findByCompanyIdAndSourceRef(company.getId(), sourceRef).stream()
                .filter(t -> t.getTxnType() == type)
                .toList();
    }

    static BigDecimal amountOn(List<CashTransaction> rows, Long accountId) {
        return rows.stream().filter(r -> r.getCashBankAccountId().equals(accountId))
                .map(CashTransaction::getAmount).reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    BigDecimal bookBalance(Long accountId) {
        BigDecimal b = cashTxns.bookBalance(accountId);
        return b == null ? BigDecimal.ZERO : b;
    }
}
