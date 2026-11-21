package com.erp.modules.sales.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.entity.CashTransaction;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.repository.CashTransactionRepository;
import com.erp.modules.cashbank.service.CashBankAccountService;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.dto.JournalLineDto;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
import com.erp.modules.gl.service.JournalService;
import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
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
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.PriceListService;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PosDashboardDto;
import com.erp.modules.sales.domain.dto.PosPayoutDto;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.PosSaleReturnDto;
import com.erp.modules.sales.domain.dto.PosSaleReturnRequest;
import com.erp.modules.sales.domain.dto.PosSaleReturnsDto;
import com.erp.modules.sales.domain.dto.PosSessionDto;
import com.erp.modules.sales.domain.dto.PosTillDto;
import com.erp.modules.sales.domain.dto.SalesInvoiceDto;
import com.erp.modules.sales.domain.dto.SalesReportRowDto;
import com.erp.modules.sales.domain.enums.SalesSummaryGroupBy;
import com.erp.modules.sales.domain.dto.SalesInvoiceLineDto;
import com.erp.modules.sales.domain.dto.VatOutputSummaryDto;
import com.erp.modules.sales.domain.dto.XReadDto;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.domain.enums.PosPayoutType;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.money.MoneyDto;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventStatus;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * ADR-0072 end to end against a real Postgres: ring a 3-unit POS sale, return 1 unit, then the other
 * 2. Drives the whole chain — return rows, REFUND payout and expected cash, the SALES_REVERSAL
 * journal under the return's uid, stock back on hand at the original cost, the cash-book row, the
 * refused over-return, the refused whole-sale reverse, idempotent replay, and the reports.
 */
class PosSaleReturnServiceIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private UserBranchRepository    userBranches;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private ChartOfAccountRepository chartOfAccountRepo;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private TaxRateSeeder           taxRateSeeder;

    @Autowired private CashBankAccountService  cashBankAccountService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private PriceListService        priceListService;
    @Autowired private ProductService          productService;
    @Autowired private AgentService            agentService;
    @Autowired private CustomerService         customerService;
    @Autowired private PosTillService          tillService;
    @Autowired private PosSessionService       sessionService;
    @Autowired private PosSaleService          saleService;
    @Autowired private PosSaleReturnService    returnService;
    @Autowired private SalesInvoiceService     invoiceService;
    @Autowired private SalesInvoiceRepository  invoiceRepo;
    @Autowired private StockOnHandRepository   stockOnHandRepo;
    @Autowired private JournalService          journalService;
    @Autowired private CashTransactionRepository cashTransactions;
    @Autowired private PosDashboardQuery       dashboardQuery;
    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private TransactionTemplate     txTemplate;
    @Autowired private SalesReportQuery        salesRegister;
    @Autowired private SalesSummaryReportQuery salesSummary;
    @Autowired private ProfitabilityReportQuery profitability;
    @Autowired private org.springframework.jdbc.core.JdbcTemplate jdbc;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private Long    cashierId;
    private Long    customerId;
    private String  pcsUid;
    private String  priceListUid;
    private String  sessionUid;
    private String  cashGlCode;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("POS Return IT Org"));
        company = companies.save(new Company(org, "POSRET", "POS Return Co"));
        branch  = branches.save(new Branch(company, "PR1", "POS Return Branch"));

        AppUser root = new AppUser("posret_root", passwordEncoder.encode("R00t!Pass1"), "POS Return Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();

        setRootCtx();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());

        var cashAccount = chartOfAccountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "1000")
                .or(() -> chartOfAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "1100"))
                .orElseThrow(() -> new IllegalStateException("No cash GL account seeded"));
        cashGlCode = cashAccount.getAccountCode();
        cashBankAccountService.create(new CreateCashBankAccountRequest(
                company.getUid(), null, "POS Cash", CashBankAccountType.CASH,
                null, null, null, cashAccount.getUid(), true));

        pcsUid       = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        priceListUid = priceListService.create(new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        AppUser cashier = new AppUser("posret_cashier", passwordEncoder.encode("C@sh1Pass"), "POS Return Cashier");
        cashier.setOrganisationId(org.getId());
        cashier = users.save(cashier);
        cashierId = cashier.getId();
        userBranches.save(new UserBranch(cashierId, branch, rootId));

        agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "POS Return Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.INTERNAL, cashierId));

        customerId = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Walk-In Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).id();

        PosTillDto till = tillService.createTill(new CreatePosTillRequest(
                company.getUid(), branch.getId(), "Till 1", null));
        setCashierCtx();
        PosSessionDto session = sessionService.openSession(
                new OpenSessionRequest(till.uid(), BigDecimal.ZERO));
        sessionUid = session.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void partialThenFinalReturn_movesCashStockAndLedgerExactlyOnce_andBlocksTheWholeSaleReverse() {
        // ---- a 3-unit sale at 1000 + 18% VAT (gross 3540), 50 in stock at cost 500 -------------
        setRootCtx();
        ProductDto product = productService.create(new CreateProductRequest(
                company.getUid(), null, "Returnable Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));
        receiveStock(product, new BigDecimal("50"), new BigDecimal("500"));

        setCashierCtx();
        SalesInvoiceDto sale = saleService.processSale(null, cashSaleRequest(product.id(),
                new BigDecimal("3")));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED, sale.uid()));
        assertThat(sale.grossTotalAmount()).isEqualByComparingTo("3540");
        assertThat(requireSoh(product.id()).getQuantity()).isEqualByComparingTo("47");
        String lineUid = invoiceService.listLines(sale.uid()).get(0).uid();

        // ---- a cashier with no supervisor approval is refused, and nothing is written ----------
        assertThatThrownBy(() -> returnService.returnItems(sale.uid(), null, request(lineUid, "1", null)))
                .isInstanceOf(ForbiddenException.class);
        assertThat(sessionPayouts()).isEmpty();

        // ---- return ONE unit (root = supervisor authority), with an idempotency key -------------
        setRootCtx();
        PosSaleReturnDto first = returnService.returnItems(sale.uid(), "ret-key-1",
                request(lineUid, "1", null));
        assertThat(first.returnNo()).isEqualTo("PRT-0001");
        assertThat(first.netAmount()).isEqualByComparingTo("1000");
        assertThat(first.vatAmount()).isEqualByComparingTo("180");
        assertThat(first.grossAmount()).isEqualByComparingTo("1180");
        assertThat(first.refundTender()).isEqualTo("CASH");
        assertThat(first.sessionUid()).isEqualTo(sessionUid);
        assertThat(first.lines()).singleElement().satisfies(l -> {
            assertThat(l.invoiceLineUid()).isEqualTo(lineUid);
            assertThat(l.qtyReturned()).isEqualByComparingTo("1");
            assertThat(l.grossAmount()).isEqualByComparingTo("1180");
        });

        // the sale itself is untouched
        assertThat(invoiceRepo.findByUid(sale.uid()).orElseThrow().getStatus())
                .isEqualTo(InvoiceStatus.FINALISED);

        // the REFUND payout took the cash out of the drawer: expected cash drops by the refund
        List<PosPayoutDto> payouts = sessionPayouts();
        assertThat(payouts).singleElement().satisfies(p -> {
            assertThat(p.payoutType()).isEqualTo(PosPayoutType.REFUND);
            assertThat(p.amount()).isEqualByComparingTo("1180");
            assertThat(p.journalEntryUid()).as("a REFUND payout posts no journal of its own").isNull();
        });
        assertThat(first.payoutUid()).isEqualTo(payouts.get(0).uid());
        XReadDto xRead = sessionService.xRead(sessionUid);
        assertThat(xRead.cashTenderAmount()).isEqualByComparingTo("3540");
        assertThat(xRead.totalPayoutsNetAmount()).isEqualByComparingTo("1180");
        assertThat(xRead.expectedCashAmount()).as("0 float + 3540 cash - 1180 refund")
                .isEqualByComparingTo("2360");
        assertThat(xRead.totalSalesAmount()).as("turnover is net of the return")
                .isEqualByComparingTo("2360");

        // ---- idempotent retry: same key, same return, no second refund -------------------------
        PosSaleReturnDto replay = returnService.returnItems(sale.uid(), "ret-key-1",
                request(lineUid, "1", null));
        assertThat(replay.uid()).isEqualTo(first.uid());
        assertThat(sessionPayouts()).as("a replay must not refund twice").hasSize(1);

        // ---- over-return: 3 sold, 1 back, asking for 3 more --------------------------------------
        assertThatThrownBy(() -> returnService.returnItems(sale.uid(), null,
                request(lineUid, "3", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Only 2 of " + "Returnable Widget" + " can still be returned.");

        // ---- the return's event: stock back, SALES_REVERSAL under the return uid, cash book -----
        dispatcher.dispatchOne(pendingEvent(DomainEventType.POS_SALE_RETURNED, first.uid()));
        assertThat(requireSoh(product.id()).getQuantity())
                .as("one unit is back on hand").isEqualByComparingTo("48");

        JournalEntryDto journal = journalService.findPostedBySource(company.getId(),
                JournalSourceType.SALES_REVERSAL, first.uid()).orElseThrow(
                        () -> new AssertionError("no SALES_REVERSAL journal under the return uid"));
        assertThat(journal.totalDebit()).isEqualByComparingTo("1180");
        assertThat(journal.totalCredit()).isEqualByComparingTo("1180");
        JournalEntryDto original = journalService.findPostedBySource(company.getId(),
                JournalSourceType.SALES, sale.uid()).orElseThrow();
        List<String> originalCreditAccounts = original.lines().stream()
                .filter(l -> l.creditAmount().signum() > 0).map(JournalLineDto::accountCode).toList();
        assertThat(journal.lines().stream().filter(l -> l.debitAmount().signum() > 0)
                .map(JournalLineDto::accountCode).toList())
                .as("Dr the very revenue and VAT accounts the sale credited")
                .containsExactlyInAnyOrderElementsOf(originalCreditAccounts);
        assertThat(journal.lines().stream()
                .filter(l -> l.debitAmount().signum() > 0 && l.debitAmount().compareTo(
                        new BigDecimal("1000")) == 0).count()).isEqualTo(1);
        assertThat(journal.lines().stream().filter(l -> l.creditAmount().signum() > 0).toList())
                .singleElement().satisfies(l -> {
                    assertThat(l.accountCode()).as("Cr the till's cash account").isEqualTo(cashGlCode);
                    assertThat(l.creditAmount()).isEqualByComparingTo("1180");
                });

        // the cost came back: Dr Inventory / Cr COGS 500 under the return uid (source COGS)
        JournalEntryDto cogs = journalService.findPostedBySource(company.getId(),
                JournalSourceType.COGS, first.uid()).orElseThrow(
                        () -> new AssertionError("no COGS reversal journal for the return"));
        assertThat(cogs.totalDebit()).isEqualByComparingTo("500");

        // the cash book carries the refund exactly once, mirroring that journal's cash leg
        List<CashTransaction> cashRows = cashTransactions.findByCompanyIdAndSourceRef(
                company.getId(), first.uid());
        assertThat(cashRows).singleElement().satisfies(t -> {
            assertThat(t.getTxnType()).isEqualTo(CashTxnType.SALE_REFUND);
            assertThat(t.getDirection()).isEqualTo(CashTxnDirection.OUT);
            assertThat(t.getAmount()).isEqualByComparingTo("1180");
        });

        // ---- the sale/receipt DTOs tell the till what it needs ---------------------------------
        SalesInvoiceDto annotated = returnService.annotate(invoiceService.getByUid(sale.uid()));
        assertThat(annotated.hasReturns()).isTrue();
        assertThat(annotated.returnedGross()).isEqualByComparingTo("1180");
        List<SalesInvoiceLineDto> lines = returnService.annotateLines(invoiceService.listLines(sale.uid()));
        assertThat(lines.get(0).qtyReturned()).isEqualByComparingTo("1");
        PosSaleReturnsDto summary = returnService.listForSale(sale.uid());
        assertThat(summary.returnable()).isTrue();
        assertThat(summary.lines()).singleElement().satisfies(l -> {
            assertThat(l.qtySold()).isEqualByComparingTo("3");
            assertThat(l.qtyReturned()).isEqualByComparingTo("1");
            assertThat(l.qtyRemaining()).isEqualByComparingTo("2");
        });

        // ---- the whole-sale reverse and the back-office void are now refused (409) --------------
        assertThatThrownBy(() -> saleService.reverseSale(sale.uid(), "wrong sale"))
                .isInstanceOf(ConflictException.class)
                .hasMessage("Some items on this sale were already returned. Return the remaining "
                        + "items with Return items.");
        assertThatThrownBy(() -> invoiceService.voidInvoice(sale.uid(),
                new com.erp.modules.sales.domain.dto.VoidInvoiceRequest("back office")))
                .isInstanceOf(ConflictException.class);

        // ---- return the REMAINING two: the exact remainder, no drift ----------------------------
        PosSaleReturnDto second = returnService.returnItems(sale.uid(), null,
                request(lineUid, "2", null));
        assertThat(second.returnNo()).isEqualTo("PRT-0002");
        assertThat(second.grossAmount()).isEqualByComparingTo("2360");
        assertThat(first.grossAmount().add(second.grossAmount()))
                .as("the two returns add back to exactly what the customer paid")
                .isEqualByComparingTo(sale.grossTotalAmount());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.POS_SALE_RETURNED, second.uid()));
        assertThat(requireSoh(product.id()).getQuantity())
                .as("all three units are back").isEqualByComparingTo("50");

        // nothing left to return
        assertThatThrownBy(() -> returnService.returnItems(sale.uid(), null,
                request(lineUid, "1", null)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("fully returned");
        assertThat(returnService.listForSale(sale.uid()).returnable()).isFalse();
        assertThat(sessionService.xRead(sessionUid).expectedCashAmount())
                .as("every shilling taken has been handed back").isEqualByComparingTo("0");

        // ---- reports: the sale stays FINALISED, so each one must net the returns ----------------
        LocalDate today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
        PosDashboardDto dashboard = dashboardQuery.report(today.minusDays(1), today.plusDays(1));
        assertThat(dashboard.grossTotal()).as("dashboard gross is net of returns")
                .isEqualByComparingTo("0");
        assertThat(dashboard.tenders()).filteredOn(t -> "CASH".equals(t.tenderType()))
                .singleElement().satisfies(t -> assertThat(t.amount()).isEqualByComparingTo("0"));
        assertThat(dashboard.departments()).allSatisfy(d ->
                assertThat(d.gross()).isEqualByComparingTo("0"));

        setRootCtx();
        VatOutputSummaryDto negatives = invoiceService.findVatVoidSummaryForPeriod(
                company.getId(), today.minusDays(1), today.plusDays(1));
        assertThat(negatives.totalOutputVat())
                .as("the VAT return's negative: all 540 of the sale's VAT was refunded")
                .isEqualByComparingTo("540");
    }

    /**
     * ADR-0072 follow-up: the Sales Register, Sales Summary and Profitability report net a POS item
     * return - revenue by the return's date, cost from the SALE_REVERSAL movement - instead of
     * counting the returned unit as sold. Sale of 3 at 1000 + 18% VAT (cost 500 each), 1 returned.
     */
    @Test
    void salesRegisterSummaryAndProfitability_netAPartialReturn_byTheReturnDate() {
        setRootCtx();
        ProductDto product = productService.create(new CreateProductRequest(
                company.getUid(), null, "Netted Widget", null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
        productService.setPrice(product.uid(),
                new SetProductPriceRequest(priceListUid, new MoneyDto("1000", "TZS")));
        receiveStock(product, new BigDecimal("50"), new BigDecimal("500"));
        setCashierCtx();
        SalesInvoiceDto sale = saleService.processSale(null, cashSaleRequest(product.id(),
                new BigDecimal("3")));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.SALE_FINALISED, sale.uid()));
        String lineUid = invoiceService.listLines(sale.uid()).get(0).uid();

        setRootCtx();
        PosSaleReturnDto ret = returnService.returnItems(sale.uid(), null, request(lineUid, "1", null));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.POS_SALE_RETURNED, ret.uid()));

        LocalDate today = LocalDate.now(ZoneId.of("Africa/Dar_es_Salaam"));
        Long co = company.getId();

        // ---- same day: 3 sold - 1 returned = 2; cost 3x500 - 1x500 = 1000 --------------------
        SalesReportRowDto reg = salesRegister.report(co, today, today, null, null, null, null)
                .rows().stream().filter(r -> "Netted Widget".equals(r.productName()))
                .findFirst().orElseThrow();
        assertThat(reg.qtySold()).isEqualByComparingTo("2");
        assertThat(reg.vat()).isEqualByComparingTo("360");
        assertThat(reg.amount()).as("gross 3540 - 1180").isEqualByComparingTo("2360");
        assertThat(reg.margin()).as("net 2000 - cost 1000").isEqualByComparingTo("1000");

        var prof = profitability.report(co, today, today, null);
        var profRow = prof.rows().stream().filter(r -> "Netted Widget".equals(r.productName()))
                .findFirst().orElseThrow();
        assertThat(profRow.qtySold()).isEqualByComparingTo("2");
        assertThat(profRow.grossSales()).isEqualByComparingTo("2360");
        assertThat(profRow.vatAmount()).isEqualByComparingTo("360");
        assertThat(profRow.netAmount()).isEqualByComparingTo("2000");
        assertThat(profRow.costOfSales()).isEqualByComparingTo("1000");
        assertThat(profRow.profit()).isEqualByComparingTo("1000");
        assertThat(prof.totals().netAmount()).isEqualByComparingTo("2000");
        assertThat(prof.totals().profit()).isEqualByComparingTo("1000");

        for (SalesSummaryGroupBy by : SalesSummaryGroupBy.values()) {
            var sum = salesSummary.report(co, today, today, by, null);
            assertThat(sum.totals().grossAmount()).as(by + " gross").isEqualByComparingTo("2360");
            assertThat(sum.totals().qty()).as(by + " qty").isEqualByComparingTo("2");
            assertThat(sum.totals().netAmount()).as(by + " net").isEqualByComparingTo("2000");
            assertThat(sum.totals().costOfSales()).as(by + " cost").isEqualByComparingTo("1000");
            assertThat(sum.totals().margin()).as(by + " margin").isEqualByComparingTo("1000");
            assertThat(sum.totals().invoiceCount()).as(by + " invoices: a return is not a sale")
                    .isEqualTo(1);
        }

        // ---- the sale was in an earlier window: the return is a negative on ITS day ------------
        jdbc.update("UPDATE sales_invoices SET finalised_at = finalised_at - INTERVAL '3 days'"
                + " WHERE uid = ?", sale.uid());
        SalesReportRowDto earlier = salesRegister.report(co, today.minusDays(4), today.minusDays(2),
                null, null, null, null).rows().stream()
                .filter(r -> "Netted Widget".equals(r.productName())).findFirst().orElseThrow();
        assertThat(earlier.qtySold()).as("the sale's own day still shows all 3").isEqualByComparingTo("3");
        assertThat(earlier.amount()).isEqualByComparingTo("3540");
        assertThat(earlier.margin()).isEqualByComparingTo("1500");

        SalesReportRowDto returnDay = salesRegister.report(co, today, today, null, null, null, null)
                .rows().stream().filter(r -> "Netted Widget".equals(r.productName()))
                .findFirst().orElseThrow();
        assertThat(returnDay.qtySold()).as("a product only returned today shows negative")
                .isEqualByComparingTo("-1");
        assertThat(returnDay.amount()).isEqualByComparingTo("-1180");
        assertThat(returnDay.margin()).as("net -1000 less cost -500").isEqualByComparingTo("-500");
        var daily = salesSummary.report(co, today, today, SalesSummaryGroupBy.DAY, null);
        assertThat(daily.rows()).singleElement().satisfies(r -> {
            assertThat(r.groupKey()).isEqualTo(today.toString());
            assertThat(r.grossAmount()).isEqualByComparingTo("-1180");
            assertThat(r.costOfSales()).isEqualByComparingTo("-500");
            assertThat(r.invoiceCount()).isZero();
        });
        assertThat(profitability.report(co, today, today, null).totals().profit())
                .isEqualByComparingTo("-500");
    }

    // ------------------------------------------------------------------------- helpers

    private List<PosPayoutDto> sessionPayouts() {
        return sessionService.listPayouts(sessionUid);
    }

    private static PosSaleReturnRequest request(String lineUid, String qty, String authoriserUid) {
        return new PosSaleReturnRequest("customer returned the goods", authoriserUid,
                List.of(new PosSaleReturnRequest.LineDto(lineUid, new BigDecimal(qty))));
    }

    private void receiveStock(ProductDto product, BigDecimal qty, BigDecimal cost) {
        StockReceivedPayload payload = new StockReceivedPayload(
                product.uid(), company.getId(), branch.getId(), Instant.now(),
                List.of(new StockReceivedPayload.LineItem(
                        product.id(), product.uid(), null, qty, cost)));
        txTemplate.execute(s -> {
            outboxPublisher.publish(DomainEventType.STOCK_RECEIVED,
                    DomainEventType.AGG_GOODS_RECEIPT, product.id(), product.uid(),
                    company.getId(), branch.getId(), payload);
            return null;
        });
        dispatcher.dispatchOne(pendingEvent(DomainEventType.STOCK_RECEIVED, product.uid()));
    }

    private Long pendingEvent(String eventType, String aggregateUid) {
        return domainEventRepo.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> aggregateUid.equals(e.getAggregateUid()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .reduce((a, b) -> b)
                .map(DomainEvent::getId)
                .orElseThrow(() -> new AssertionError("No PENDING event of type: " + eventType));
    }

    private StockOnHand requireSoh(Long productId) {
        return stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(company.getId(), branch.getId(), productId)
                .orElseThrow(() -> new AssertionError("No on-hand row for productId=" + productId));
    }

    private PosSaleRequest cashSaleRequest(Long productId, BigDecimal qty) {
        return new PosSaleRequest(
                sessionUid, customerId, null, "TZS",
                List.of(new PosSaleRequest.LineItem(productId, unitId(), qty, null, null)),
                null, BigDecimal.TEN, null, null);
    }

    private Long unitId() {
        return unitService.getByUid(pcsUid).id();
    }

    private void setRootCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "posret_root", true, company.getId(), branch.getId(), null));
    }

    private void setCashierCtx() {
        RequestContext.set(new RequestContext.Principal(
                cashierId, "posret_cashier", false, company.getId(), branch.getId(), null));
    }
}
