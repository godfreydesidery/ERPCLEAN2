package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.PosSaleReturnRequest;
import com.erp.modules.sales.domain.dto.PosSaleRequest;
import com.erp.modules.sales.domain.dto.QuoteRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.RestaurantQuoteDto;
import com.erp.modules.sales.domain.enums.TenderType;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

import com.erp.modules.cashbank.domain.dto.CreateCashBankAccountRequest;
import com.erp.modules.cashbank.domain.enums.CashBankAccountType;
import com.erp.modules.cashbank.service.CashBankAccountService;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.gl.service.ChartOfAccountService;
import com.erp.modules.gl.service.FiscalCalendarService;
import com.erp.modules.gl.service.GlConfigService;
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
import com.erp.modules.products.domain.dto.AddComponentRequest;
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
import com.erp.modules.sales.domain.dto.TransferRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.AddRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.dto.BillRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.CancelRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.CreatePosTillRequest;
import com.erp.modules.sales.domain.dto.CreatePrepStationRequest;
import com.erp.modules.sales.domain.dto.CreateRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.CreateRestaurantTableRequest;
import com.erp.modules.sales.domain.dto.FireRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.KitchenTicketDto;
import com.erp.modules.sales.domain.dto.ModifierGroupDto;
import com.erp.modules.sales.domain.dto.SaveModifierGroupRequest;
import com.erp.modules.sales.domain.dto.UpdateRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.dto.UpdateRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.MergeRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.CloseSessionRequest;
import com.erp.modules.sales.domain.dto.PayRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.ReconcileSessionRequest;
import com.erp.modules.sales.domain.dto.ZReadBreakdownDto;
import com.erp.modules.sales.domain.dto.ZReadDto;
import com.erp.modules.sales.domain.dto.PrepSlipDto;
import com.erp.modules.sales.domain.dto.PrepStationDto;
import com.erp.modules.sales.domain.dto.PrepStationCategoryDto;
import com.erp.platform.common.api.ConflictException;
import com.erp.modules.sales.domain.dto.ReplaceCategoryRoutesRequest;
import com.erp.modules.sales.domain.dto.SetCategoryRouteRequest;
import com.erp.modules.sales.domain.dto.RestaurantBillDto;
import com.erp.modules.sales.domain.dto.RestaurantOrderDto;
import com.erp.modules.sales.domain.dto.RestaurantOrderLineDto;
import com.erp.modules.sales.domain.dto.RestaurantPaymentDto;
import com.erp.modules.sales.domain.dto.RestaurantSendResultDto;
import com.erp.modules.sales.domain.dto.RestaurantSplitResultDto;
import com.erp.modules.sales.domain.dto.SplitRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.StationSlipsDto;
import com.erp.modules.sales.domain.dto.RestaurantTableDto;
import com.erp.modules.sales.domain.dto.RestaurantVoidResultDto;
import com.erp.modules.sales.domain.dto.VoidRestaurantOrderLineRequest;
import com.erp.modules.sales.domain.dto.RestaurantDashboardDto;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.enums.DocumentOrigin;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.domain.enums.PrepSlipKind;
import com.erp.modules.sales.domain.enums.RestaurantOrderLineStatus;
import com.erp.modules.sales.domain.enums.RestaurantOrderStatus;
import com.erp.modules.sales.domain.enums.RestaurantOrderType;
import com.erp.modules.sales.domain.enums.RestaurantTableState;
import com.erp.modules.sales.domain.exception.PosSaleFlowException;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
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
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Restaurant orders end to end against a real Postgres (ADR-0065): the V107 schema, the
 * order → send → bill → pay flow the client described, kitchen/bar routing by category, voids,
 * the bill-equals-sale guarantee, and branch isolation.
 *
 * <p>The cashier holds no roles here, so their permission checks are genuine refusals; root
 * stands in for a manager where a void needs authority.
 */
class RestaurantOrderServiceIT extends PostgresIntegrationTest {

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
    @Autowired private SalesInvoiceRepository  invoiceRepo;
    @Autowired private StockOnHandRepository   stockOnHandRepo;
    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private OutboxPublisher         outboxPublisher;
    @Autowired private TransactionTemplate     txTemplate;

    @Autowired private RestaurantSetupService  setup;
    @Autowired private RestaurantOrderService  orders;
    @Autowired private RestaurantMenuService   menu;
    @Autowired private RestaurantKitchenService kitchenDisplay;
    @Autowired private RestaurantReportQuery   reports;
    @Autowired private SalesReportQuery        salesReport;
    @Autowired private RestaurantInsightsQuery insights;
    @Autowired private PosSaleService          posSales;
    @Autowired private JdbcTemplate            jdbc;
    @Autowired private PosSaleReturnService    posReturns;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private Long    orgId;
    private Long    cashierId;
    private Long    customerId;
    private Long    pcsId;
    private String  pcsUid;
    private String  priceListUid;
    private String  sessionUid;

    private PrepStationDto kitchen;
    private PrepStationDto bar;
    private RestaurantTableDto table1;
    private ProductDto tacos;
    private ProductDto latte;
    private ProductDto cake;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Restaurant IT Org"));
        orgId = org.getId();
        company = companies.save(new Company(org, "RESTO", "Cafe Restaurant Co"));
        branch  = branches.save(new Branch(company, "RS1", "Cafe Branch"));

        AppUser root = new AppUser("resto_root", passwordEncoder.encode("R00t!Pass1"), "Resto Manager");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        root   = users.save(root);
        rootId = root.getId();
        setRootCtx();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());

        String cashGlUid = chartOfAccountRepo
                .findByCompanyIdAndAccountCode(company.getId(), "1000")
                .or(() -> chartOfAccountRepo.findByCompanyIdAndAccountCode(company.getId(), "1100"))
                .map(a -> a.getUid())
                .orElseThrow();
        cashBankAccountService.create(new CreateCashBankAccountRequest(
                company.getUid(), null, "Till Cash", CashBankAccountType.CASH,
                null, null, null, cashGlUid, true));

        pcsUid       = unitService.create(new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();
        pcsId        = unitService.getByUid(pcsUid).id();
        priceListUid = priceListService.create(new CreatePriceListRequest(company.getUid(), "RETAIL", "Retail")).uid();

        AppUser cashier = new AppUser("resto_cashier", passwordEncoder.encode("C@sh1Pass"), "Asha Waiter");
        cashier.setOrganisationId(org.getId());
        cashier   = users.save(cashier);
        cashierId = cashier.getId();
        userBranches.save(new UserBranch(cashierId, branch, rootId));

        agentService.create(new CreateAgentRequest(
                company.getId(), PartyType.INDIVIDUAL, "Asha Agent",
                null, null, null, null, null, null, null, null, null, null, null, null,
                AgentKind.INTERNAL, cashierId));
        customerId = customerService.create(new CreateCustomerRequest(
                company.getId(), PartyType.INDIVIDUAL, "Walk-In Customer",
                null, null, null, null, null, null, null, null, null, null, null, null,
                CustomerKind.CASH_WALK_IN, null, null, null)).id();

        // Restaurant setup: Kitchen is the first station (auto-default), Bar takes "Drinks".
        kitchen = setup.createStation(new CreatePrepStationRequest(null, "Kitchen", "192.168.1.50", null, false));
        bar     = setup.createStation(new CreatePrepStationRequest(null, "Bar", "192.168.1.51", 9100, false));
        setup.replaceCategoryRoutes(new ReplaceCategoryRoutesRequest(null,
                List.of(new ReplaceCategoryRoutesRequest.Route("  drinks ", bar.uid()))));
        table1 = setup.createTable(new CreateRestaurantTableRequest(null, "T1", (short) 4, "Inside", 1));

        tacos = menuItem("Grilled Chicken Tacos", "12000", "Food");
        latte = menuItem("Pumpkin Spice Latte", "6000", "Drinks");
        cake  = menuItem("Brownie Cake", "7000", null);

        String tillUid = tillService.createTill(new CreatePosTillRequest(
                company.getUid(), branch.getId(), "Till 1", null)).uid();
        setCashierCtx();
        sessionUid = sessionService.openSession(new OpenSessionRequest(tillUid, BigDecimal.ZERO)).uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // The client's flow: order → kitchen/bar slips → bill → payment clears the table.
    // =========================================================================

    @Test
    void orderSendBillPay_routesFoodToKitchenAndDrinksToBar_andPaymentFreesTheTable() {
        setCashierCtx();
        RestaurantOrderDto order = orders.open(new CreateRestaurantOrderRequest(
                RestaurantOrderType.DINE_IN, table1.uid(), (short) 2, null));
        assertThat(order.orderNumber()).startsWith("ORD-");

        orders.addLine(order.uid(), item(tacos));
        orders.addLine(order.uid(), item(latte));
        order = orders.addLine(order.uid(), item(latte));   // tapped twice → one line of 2
        assertThat(order.lines()).hasSize(2);
        assertThat(lineFor(order, latte).quantity()).isEqualByComparingTo("2");

        RestaurantSendResultDto sent = orders.send(order.uid());
        assertThat(sent.slips()).extracting(PrepSlipDto::stationName)
                .containsExactlyInAnyOrder("Kitchen", "Bar");
        PrepSlipDto barSlip = slip(sent.slips(), "Bar");
        assertThat(barSlip.kind()).isEqualTo(PrepSlipKind.FIRE);
        assertThat(barSlip.printerHost()).isEqualTo("192.168.1.51");
        assertThat(barSlip.tableName()).isEqualTo("T1");
        assertThat(barSlip.byName()).isEqualTo("Asha Waiter");
        assertThat(barSlip.lines()).singleElement().satisfies(l -> {
            assertThat(l.productName()).isEqualTo("Pumpkin Spice Latte");
            assertThat(l.quantity()).isEqualByComparingTo("2");
        });
        assertThat(slip(sent.slips(), "Kitchen").lines()).singleElement()
                .satisfies(l -> assertThat(l.productName()).isEqualTo("Grilled Chicken Tacos"));
        assertThat(sent.order().pendingLineCount()).isZero();

        // A second round fires only what is new; an uncategorised item goes to the default (Kitchen).
        orders.addLine(order.uid(), item(cake));
        RestaurantSendResultDto round2 = orders.send(order.uid());
        assertThat(round2.slips()).singleElement().satisfies(s -> {
            assertThat(s.stationName()).isEqualTo("Kitchen");
            assertThat(s.round()).isEqualTo(2);
            assertThat(s.lines()).extracting(PrepSlipDto.Line::productName).containsExactly("Brownie Cake");
        });
        assertThat(orders.send(order.uid()).slips()).as("nothing new, nothing printed").isEmpty();

        assertThat(tableState()).isEqualTo(RestaurantTableState.OCCUPIED);

        RestaurantBillDto bill = orders.bill(order.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        assertThat(bill.lines()).hasSize(3);
        assertThat(bill.grossTotal()).isGreaterThan(BigDecimal.ZERO);
        assertThat(bill.printCount()).isEqualTo(1);
        assertThat(tableState()).isEqualTo(RestaurantTableState.BILLED);
        assertThat(orders.bill(order.uid(), new BillRestaurantOrderRequest(null, "TZS")).printCount())
                .as("a second print is a reprint").isEqualTo(2);

        stock(tacos, latte, cake);
        setCashierCtx();
        RestaurantPaymentDto paid = orders.pay(order.uid(), "resto-pay-1", payment(bill.grossTotal()));

        assertThat(paid.order().status()).isEqualTo(RestaurantOrderStatus.PAID);
        assertThat(paid.invoice().status()).isEqualTo(InvoiceStatus.FINALISED);
        assertThat(paid.invoice().grossTotalAmount())
                .as("the customer pays exactly what the bill showed")
                .isEqualByComparingTo(bill.grossTotal());
        SalesInvoice invoice = invoiceRepo.findByUid(paid.invoice().uid()).orElseThrow();
        assertThat(invoice.getOrigin()).isEqualTo(DocumentOrigin.POS);
        assertThat(paid.order().salesInvoiceUid()).isEqualTo(invoice.getUid());
        assertThat(tableState()).as("payment clears the order from the table")
                .isEqualTo(RestaurantTableState.FREE);

        // A retry of the same payment returns the same sale; it does not ring a second one.
        RestaurantPaymentDto replay = orders.pay(order.uid(), "resto-pay-1", payment(bill.grossTotal()));
        assertThat(replay.invoice().uid()).isEqualTo(paid.invoice().uid());
        assertThat(invoiceRepo.findAll().stream().filter(i -> i.getOrigin() == DocumentOrigin.POS))
                .hasSize(1);
        // A different payment attempt on a paid order is refused.
        assertThatThrownBy(() -> orders.pay(paid.order().uid(), "resto-pay-2", payment(bill.grossTotal())))
                .isInstanceOf(PosSaleFlowException.class)
                .hasMessageContaining("already been paid");
    }

    // =========================================================================
    // Order discount: quoted first, then charged exactly; percent or amount.
    // =========================================================================

    @Test
    void discount_byPercentOrAmount_isQuotedThenChargedExactly() {
        setCashierCtx();
        RestaurantOrderDto order = openWith(tacos);                 // 12,000 net
        orders.addLine(order.uid(), item(latte));                    //  6,000 net
        RestaurantQuoteDto full = orders.quote(order.uid(),
                new QuoteRestaurantOrderRequest(customerId, "TZS", null, null));
        BigDecimal gross = full.grossTotal();                        // + 18% VAT = 21,240
        assertThat(gross).isEqualByComparingTo("21240");
        assertThat(full.discountTotal()).isEqualByComparingTo("0");

        // Off the VAT-inclusive total, whatever the price basis.
        RestaurantQuoteDto tenPct = orders.quote(order.uid(),
                new QuoteRestaurantOrderRequest(customerId, "TZS", new BigDecimal("10"), null));
        assertThat(tenPct.grossTotal()).isEqualByComparingTo("19116");
        assertThat(tenPct.discountTotal()).isEqualByComparingTo("2124");

        RestaurantQuoteDto off = orders.quote(order.uid(),
                new QuoteRestaurantOrderRequest(customerId, "TZS", null, new BigDecimal("5000")));
        assertThat(off.grossTotal()).isEqualByComparingTo("16240");
        assertThat(off.discountTotal()).isEqualByComparingTo("5000");
        assertThat(orders.get(order.uid()).billPrintCount()).as("a quote prints nothing").isZero();

        assertThatThrownBy(() -> orders.quote(order.uid(), new QuoteRestaurantOrderRequest(
                customerId, "TZS", new BigDecimal("10"), new BigDecimal("100"))))
                .hasMessageContaining("not both");
        assertThatThrownBy(() -> orders.quote(order.uid(), new QuoteRestaurantOrderRequest(
                customerId, "TZS", null, new BigDecimal("99999"))))
                .hasMessageContaining("larger than the bill");

        stock(tacos, latte);
        setCashierCtx();
        RestaurantPaymentDto paid = orders.pay(order.uid(), "resto-disc-1",
                new PayRestaurantOrderRequest(sessionUid, customerId, "TZS", null, null,
                        off.grossTotal(), null, null, null, new BigDecimal("5000"), null));
        assertThat(paid.invoice().grossTotalAmount()).isEqualByComparingTo("16240");
        SalesInvoice invoice = invoiceRepo.findByUid(paid.invoice().uid()).orElseThrow();
        assertThat(jdbc.queryForObject(
                "select coalesce(sum(line_discount_amount),0) from sales_invoice_lines where invoice_id = ?",
                BigDecimal.class, invoice.getId()))
                .as("carried as ordinary line discounts, net of VAT on an exclusive price list")
                .isCloseTo(new BigDecimal("4237.29"), org.assertj.core.data.Offset.offset(new BigDecimal("1.00")));
    }

    @Test
    void discount_withoutTheQuotedTotal_isNotChargedAgainstTheUndiscountedBill() {
        setCashierCtx();
        RestaurantOrderDto order = openWith(tacos);
        RestaurantBillDto bill = orders.bill(order.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos);
        setCashierCtx();
        assertThatThrownBy(() -> orders.pay(order.uid(), null,
                new PayRestaurantOrderRequest(sessionUid, customerId, "TZS", null, null,
                        bill.grossTotal(), null, null, new BigDecimal("10"), null, null)))
                .isInstanceOfSatisfying(PosSaleFlowException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PRICE_CHANGED"));
        assertThat(invoiceRepo.findAll()).isEmpty();
    }

    // =========================================================================
    // Completed bills: today's paid orders, newest first.
    // =========================================================================

    @Test
    void paidToday_listsTheDaysCompletedBills_andNotTheOpenOnes() {
        setCashierCtx();
        RestaurantOrderDto paidOrder = openWith(tacos);
        RestaurantBillDto bill = orders.bill(paidOrder.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos);
        setCashierCtx();
        orders.pay(paidOrder.uid(), "resto-today-1", payment(bill.grossTotal()));
        orders.open(new CreateRestaurantOrderRequest(RestaurantOrderType.TAKEAWAY, null, null, null));

        assertThat(orders.listPaid(null, null)).singleElement().satisfies(p -> {
            assertThat(p.order().uid()).isEqualTo(paidOrder.uid());
            assertThat(p.paidTotal()).isEqualByComparingTo(bill.grossTotal());
            assertThat(p.invoiceNumber()).isNotBlank();
        });
        assertThat(orders.listPaid(null, java.time.LocalDate.now().minusDays(1)))
                .as("yesterday had none").isEmpty();
    }

    // =========================================================================
    // Complimentary: a manager's approval, and an expense instead of cash.
    // =========================================================================

    @Test
    void complimentary_needsAManager_andPostsToTheExpenseAccountNotCash() {
        setCashierCtx();
        RestaurantOrderDto order = openWith(tacos);
        RestaurantBillDto bill = orders.bill(order.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos);
        setCashierCtx();
        var free = new PosSaleRequest.PosTender(TenderType.COMPLIMENTARY, bill.grossTotal(),
                null, null, null, null, null);
        assertThatThrownBy(() -> orders.pay(order.uid(), "resto-comp-1",
                new PayRestaurantOrderRequest(sessionUid, customerId, "TZS", List.of(free), null,
                        bill.grossTotal(), null, null)))
                .isInstanceOfSatisfying(PosSaleFlowException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("COMPLIMENTARY_APPROVAL_REQUIRED"));
        assertThat(invoiceRepo.findAll()).as("nothing was posted").isEmpty();

        // The cashier may not name themselves.
        String cashierUid = users.findById(cashierId).orElseThrow().getUid();
        var self = new PosSaleRequest.PosTender(TenderType.COMPLIMENTARY, bill.grossTotal(),
                null, null, null, null, null, cashierUid);
        assertThatThrownBy(() -> orders.pay(order.uid(), "resto-comp-2",
                new PayRestaurantOrderRequest(sessionUid, customerId, "TZS", List.of(self), null,
                        bill.grossTotal(), null, null)))
                .isInstanceOf(PosSaleFlowException.class);

        String managerUid = users.findById(rootId).orElseThrow().getUid();
        var approved = new PosSaleRequest.PosTender(TenderType.COMPLIMENTARY, bill.grossTotal(),
                null, null, null, null, null, managerUid);
        RestaurantPaymentDto paid = orders.pay(order.uid(), "resto-comp-3",
                new PayRestaurantOrderRequest(sessionUid, customerId, "TZS", List.of(approved), null,
                        bill.grossTotal(), null, null));
        assertThat(paid.order().status()).isEqualTo(RestaurantOrderStatus.PAID);
        assertThat(paid.invoice().grossTotalAmount()).isEqualByComparingTo(bill.grossTotal());

        setRootCtx();
        dispatchPending(DomainEventType.SALE_FINALISED, paid.invoice().uid());
        BigDecimal toExpense = jdbc.queryForObject(
                "select coalesce(sum(l.debit_amount),0) from journal_lines l "
                        + "join chart_of_accounts a on a.id = l.account_id "
                        + "where a.company_id = ? and a.account_code = '5176'",
                BigDecimal.class, company.getId());
        assertThat(toExpense).as("the free meal is an expense").isEqualByComparingTo(bill.grossTotal());
    }

    // =========================================================================
    // A total the customer was not shown is never charged.
    // =========================================================================

    @Test
    void pay_whenTheShownTotalDiffers_refusesWithPriceChanged_andPostsNothing() {
        setCashierCtx();
        RestaurantOrderDto order = openWith(tacos);
        orders.bill(order.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos);
        setCashierCtx();

        assertThatThrownBy(() -> orders.pay(order.uid(), null, payment(new BigDecimal("1"))))
                .isInstanceOfSatisfying(PosSaleFlowException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PRICE_CHANGED"));
        assertThat(invoiceRepo.findAll()).as("nothing was posted").isEmpty();
        assertThat(orders.get(order.uid()).status()).isEqualTo(RestaurantOrderStatus.BILLED);
    }

    @Test
    void addingAfterTheBill_reopensTheOrder_soTheOldBillCannotBeCharged() {
        setCashierCtx();
        RestaurantOrderDto order = openWith(tacos);
        RestaurantBillDto bill = orders.bill(order.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        RestaurantOrderDto after = orders.addLine(order.uid(), item(latte));
        assertThat(after.status()).isEqualTo(RestaurantOrderStatus.OPEN);

        stock(tacos, latte);
        setCashierCtx();
        assertThatThrownBy(() -> orders.pay(order.uid(), null, payment(bill.grossTotal())))
                .isInstanceOfSatisfying(PosSaleFlowException.class,
                        e -> assertThat(e.getErrorCode()).isEqualTo("PRICE_CHANGED"));
    }

    // =========================================================================
    // Unsent items are free to change; sent items are voided, with authority, and slip the station.
    // =========================================================================

    @Test
    void sentItems_cannotBeDeleted_andVoidNeedsAManager_thenPrintsAVoidSlip() {
        setCashierCtx();
        RestaurantOrderDto order = orders.open(new CreateRestaurantOrderRequest(null, table1.uid(), null, null));
        order = orders.addLine(order.uid(), new AddRestaurantOrderLineRequest(latte.id(), pcsId, new BigDecimal("3"), null));
        String pendingUid = order.lines().get(0).uid();
        // Unsent: editable and deletable.
        order = orders.deleteLine(order.uid(), pendingUid);
        assertThat(order.lines()).isEmpty();

        order = orders.addLine(order.uid(), new AddRestaurantOrderLineRequest(latte.id(), pcsId, new BigDecimal("3"), null));
        orders.send(order.uid());
        String orderUid = order.uid();
        String sentUid = orders.get(orderUid).lines().get(0).uid();

        assertThatThrownBy(() -> orders.deleteLine(orderUid, sentUid))
                .hasMessageContaining("Void it instead");
        assertThatThrownBy(() -> orders.voidLine(orderUid, sentUid,
                new VoidRestaurantOrderLineRequest("Customer changed mind", BigDecimal.ONE, null)))
                .as("a cashier without the void permission and no manager approval is refused")
                .isInstanceOf(ForbiddenException.class);

        setRootCtx();  // a manager voids one of the three
        RestaurantVoidResultDto voided = orders.voidLine(orderUid, sentUid,
                new VoidRestaurantOrderLineRequest("Customer changed mind", BigDecimal.ONE, null));
        assertThat(voided.slips()).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo(PrepSlipKind.VOID);
            assertThat(s.stationName()).isEqualTo("Bar");
            assertThat(s.lines().get(0).quantity()).isEqualByComparingTo("1");
            assertThat(s.lines().get(0).voidReason()).isEqualTo("Customer changed mind");
        });
        assertThat(voided.order().lines()).as("one line split into SENT x2 and VOIDED x1").hasSize(2);
        assertThat(voided.order().lines().stream()
                .filter(l -> l.status() == RestaurantOrderLineStatus.SENT)
                .map(RestaurantOrderLineDto::quantity).findFirst().orElseThrow())
                .isEqualByComparingTo("2");

        RestaurantBillDto bill = orders.bill(orderUid, new BillRestaurantOrderRequest(customerId, "TZS"));
        assertThat(bill.lines()).singleElement()
                .satisfies(l -> assertThat(l.quantity()).isEqualByComparingTo("2"));
    }

    @Test
    void cancel_isFreeBeforeSending_butNeedsAReasonAndAuthorityAfter() {
        setCashierCtx();
        RestaurantOrderDto unsent = openWith(cake);
        RestaurantVoidResultDto freeCancel = orders.cancel(unsent.uid(), null);
        assertThat(freeCancel.order().status()).isEqualTo(RestaurantOrderStatus.CANCELLED);
        assertThat(freeCancel.slips()).isEmpty();

        RestaurantOrderDto sent = openWith(tacos);
        orders.send(sent.uid());
        assertThatThrownBy(() -> orders.cancel(sent.uid(), new CancelRestaurantOrderRequest(null, null)))
                .hasMessageContaining("reason");
        assertThatThrownBy(() -> orders.cancel(sent.uid(), new CancelRestaurantOrderRequest("Left", null)))
                .isInstanceOf(ForbiddenException.class);

        setRootCtx();
        RestaurantVoidResultDto cancelled = orders.cancel(sent.uid(), new CancelRestaurantOrderRequest("Left", null));
        assertThat(cancelled.order().status()).isEqualTo(RestaurantOrderStatus.CANCELLED);
        assertThat(cancelled.slips()).singleElement()
                .satisfies(s -> assertThat(s.kind()).isEqualTo(PrepSlipKind.VOID));
        assertThat(tableState()).isEqualTo(RestaurantTableState.FREE);
    }

    // =========================================================================
    // Slips reprint from what was stored; takeaway has no table.
    // =========================================================================

    @Test
    void slipsReprint_andTakeawayNeedsNoTable() {
        setCashierCtx();
        RestaurantOrderDto takeaway = orders.open(
                new CreateRestaurantOrderRequest(RestaurantOrderType.TAKEAWAY, null, null, null));
        orders.addLine(takeaway.uid(), new AddRestaurantOrderLineRequest(latte.id(), pcsId, null, "no sugar"));
        orders.send(takeaway.uid());

        List<PrepSlipDto> reprint = orders.slips(takeaway.uid(), null, bar.uid());
        assertThat(reprint).singleElement().satisfies(s -> {
            assertThat(s.reprint()).isTrue();
            assertThat(s.tableName()).isNull();
            assertThat(s.lines().get(0).note()).isEqualTo("no sugar");
        });
        assertThat(orders.slips(takeaway.uid(), 1, kitchen.uid())).isEmpty();

        assertThatThrownBy(() -> orders.open(new CreateRestaurantOrderRequest(null, null, null, null)))
                .hasMessageContaining("table");
    }

    // =========================================================================
    // Tenancy: another branch's orders are out of reach.
    // =========================================================================

    @Test
    void anOrderInABranchTheCallerIsNotAssignedTo_isRefused() {
        setRootCtx();
        Branch other = branches.save(new Branch(company, "RS2", "Other Branch"));
        RequestContext.set(new RequestContext.Principal(
                rootId, "resto_root", true, company.getId(), other.getId(), null));
        RestaurantTableDto otherTable = setup.createTable(
                new CreateRestaurantTableRequest(other.getUid(), "X1", null, null, null));
        RestaurantOrderDto foreign = orders.open(
                new CreateRestaurantOrderRequest(null, otherTable.uid(), null, null));

        setCashierCtx();
        assertThatThrownBy(() -> orders.get(foreign.uid())).isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> orders.addLine(foreign.uid(), item(tacos)))
                .isInstanceOf(ForbiddenException.class);
        assertThatThrownBy(() -> orders.open(new CreateRestaurantOrderRequest(null, otherTable.uid(), null, null)))
                .as("a table of another branch cannot be used from this one")
                .hasMessageContaining("another branch");
    }

    // =========================================================================
    // Phase 3 (ADR-0066): split checks, merge orders, recipe deduction.
    // =========================================================================

    @Test
    void customerName_isKeptOnTheOrder_printedOnTheBill_andCarriedToASplitCheck() {
        // Cafe Tulia 2026-11-03: the bill should say whose it is, a person or a company.
        setCashierCtx();
        RestaurantOrderDto order = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), (short) 4, null, null, "  Mr Juma  "));
        assertThat(order.customerName()).as("trimmed").isEqualTo("Mr Juma");

        order = orders.update(order.uid(), new UpdateRestaurantOrderRequest(null, null, null, "Acme Ltd"));
        assertThat(order.customerName()).isEqualTo("Acme Ltd");
        order = orders.update(order.uid(), new UpdateRestaurantOrderRequest(null, (short) 5, null));
        assertThat(order.customerName()).as("not sent = left as it is").isEqualTo("Acme Ltd");

        orders.addLine(order.uid(), item(tacos));
        order = orders.addLine(order.uid(),
                new AddRestaurantOrderLineRequest(latte.id(), pcsId, new BigDecimal("2"), null));
        RestaurantBillDto bill = orders.bill(order.uid(), new BillRestaurantOrderRequest(null, "TZS"));
        assertThat(bill.customerName()).isEqualTo("Acme Ltd");

        RestaurantSplitResultDto split = orders.split(order.uid(), new SplitRestaurantOrderRequest(
                List.of(new SplitRestaurantOrderRequest.Item(lineFor(order, latte).uid(), BigDecimal.ONE))));
        assertThat(split.created().customerName()).isEqualTo("Acme Ltd");

        order = orders.update(order.uid(), new UpdateRestaurantOrderRequest(null, null, null, " "));
        assertThat(order.customerName()).as("blank clears it").isNull();
        assertThat(orders.bill(order.uid(), new BillRestaurantOrderRequest(null, "TZS")).customerName())
                .isNull();
    }

    @Test
    void split_movesChosenItemsToANewCheck_andEachCheckPaysItsOwnShare() {
        setCashierCtx();
        RestaurantOrderDto order = orders.open(new CreateRestaurantOrderRequest(null, table1.uid(), (short) 2, null));
        orders.addLine(order.uid(), item(tacos));
        order = orders.addLine(order.uid(),
                new AddRestaurantOrderLineRequest(latte.id(), pcsId, new BigDecimal("3"), null));
        orders.send(order.uid());
        order = orders.get(order.uid());
        String latteLine = lineFor(order, latte).uid();
        String orderUid = order.uid();

        RestaurantSplitResultDto split = orders.split(orderUid, new SplitRestaurantOrderRequest(
                List.of(new SplitRestaurantOrderRequest.Item(latteLine, BigDecimal.ONE))));

        assertThat(split.created().tableUid()).isEqualTo(table1.uid());
        assertThat(split.created().lines()).singleElement().satisfies(l -> {
            assertThat(l.quantity()).isEqualByComparingTo("1");
            assertThat(l.status()).as("a sent item stays sent: the kitchen is not asked again")
                    .isEqualTo(RestaurantOrderLineStatus.SENT);
        });
        assertThat(lineFor(split.original(), latte).quantity()).isEqualByComparingTo("2");
        assertThat(orders.send(split.created().uid()).slips()).as("nothing re-fired").isEmpty();

        RestaurantOrderDto now = orders.get(orderUid);
        assertThatThrownBy(() -> orders.split(orderUid, new SplitRestaurantOrderRequest(List.of(
                new SplitRestaurantOrderRequest.Item(lineFor(now, tacos).uid(), null),
                new SplitRestaurantOrderRequest.Item(lineFor(now, latte).uid(), null)))))
                .as("a split may not empty the original check")
                .hasMessageContaining("Nothing would be left");

        stock(tacos, latte);
        setCashierCtx();
        RestaurantBillDto small = orders.bill(split.created().uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        RestaurantBillDto big = orders.bill(orderUid, new BillRestaurantOrderRequest(customerId, "TZS"));
        orders.pay(split.created().uid(), null, payment(small.grossTotal()));
        orders.pay(orderUid, null, payment(big.grossTotal()));
        assertThat(tableState()).isEqualTo(RestaurantTableState.FREE);
        assertThat(invoiceRepo.findAll()).as("one sale per check").hasSize(2);
    }

    @Test
    void merge_movesEveryItemOntoTheTarget_andClosesTheSource() {
        setCashierCtx();
        setRootCtx();
        RestaurantTableDto table2 = setup.createTable(new CreateRestaurantTableRequest(null, "T2", null, null, 2));
        setCashierCtx();
        RestaurantOrderDto a = openWith(tacos);
        orders.send(a.uid());
        RestaurantOrderDto b = orders.open(new CreateRestaurantOrderRequest(null, table2.uid(), null, null));
        orders.addLine(b.uid(), item(latte));
        orders.bill(a.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));

        RestaurantOrderDto merged = orders.merge(a.uid(), new MergeRestaurantOrderRequest(b.uid()));

        assertThat(merged.status()).as("a billed order reopens: its bill no longer matches")
                .isEqualTo(RestaurantOrderStatus.OPEN);
        assertThat(merged.lines()).extracting(RestaurantOrderLineDto::productName)
                .containsExactly("Grilled Chicken Tacos", "Pumpkin Spice Latte");
        RestaurantOrderDto closed = orders.get(b.uid());
        assertThat(closed.status()).isEqualTo(RestaurantOrderStatus.CANCELLED);
        assertThat(closed.cancelReason()).isEqualTo("Merged into " + a.orderNumber());
        assertThat(closed.lines()).isEmpty();
        assertThatThrownBy(() -> orders.merge(a.uid(), new MergeRestaurantOrderRequest(a.uid())))
                .hasMessageContaining("different order");
    }

    @Test
    void aRecipeItem_paidThroughAnOrder_deductsItsIngredients() {
        setRootCtx();
        ProductDto milk = menuItem("Fresh Milk (L)", "2000", null);
        ProductDto recipeLatte = menuItem("House Latte", "6000", "Drinks");
        productService.addComponent(recipeLatte.uid(), new AddComponentRequest(milk.uid(), new BigDecimal("0.25")));
        stock(milk);
        BigDecimal milkBefore = onHand(milk);

        setCashierCtx();
        RestaurantOrderDto order = orders.open(new CreateRestaurantOrderRequest(null, table1.uid(), null, null));
        orders.addLine(order.uid(), new AddRestaurantOrderLineRequest(recipeLatte.id(), pcsId, new BigDecimal("2"), null));
        RestaurantBillDto bill = orders.bill(order.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        RestaurantPaymentDto paid = orders.pay(order.uid(), null, payment(bill.grossTotal()));

        setRootCtx();
        dispatchPending(DomainEventType.SALE_FINALISED, paid.invoice().uid());
        assertThat(onHand(milk)).as("two lattes use half a litre of milk")
                .isEqualByComparingTo(milkBefore.subtract(new BigDecimal("0.5")));
    }

    // =========================================================================
    // Phase 3 with schema (ADR-0066 D-4..D-7): modifiers, courses, kitchen display, roles.
    // =========================================================================

    @Test
    void modifiers_pricedAddOnsBecomeChildLines_instructionsGoToTheNote_andTheRulesHold() {
        setRootCtx();
        ProductDto syrup = menuItem("Syrup extra", "1000", "Drinks");
        menu.createGroup(new SaveModifierGroupRequest("Milk", (short) 1, (short) 1, 1, null,
                List.of(new SaveModifierGroupRequest.Option(null, "Oat milk", null, null),
                        new SaveModifierGroupRequest.Option(null, "Full milk", null, null)),
                List.of(latte.uid())));
        menu.createGroup(new SaveModifierGroupRequest("Extras", (short) 0, null, 2, null,
                List.of(new SaveModifierGroupRequest.Option(null, "Syrup", syrup.uid(), null),
                        new SaveModifierGroupRequest.Option(null, "No sugar", null, null)),
                List.of(latte.uid())));
        var groups = menu.listGroups();
        String oat = option(groups, "Oat milk");
        String full = option(groups, "Full milk");
        String syrupOpt = option(groups, "Syrup");
        String noSugar = option(groups, "No sugar");
        assertThat(menu.listLinks()).singleElement()
                .satisfies(l -> assertThat(l.productUid()).isEqualTo(latte.uid()));

        setCashierCtx();
        RestaurantOrderDto order = orders.open(new CreateRestaurantOrderRequest(null, table1.uid(), null, null));
        String orderUid = order.uid();
        assertThatThrownBy(() -> orders.addLine(orderUid, item(latte)))
                .as("Milk must be chosen").hasMessageContaining("Choose the Milk");
        assertThatThrownBy(() -> orders.addLine(orderUid, new AddRestaurantOrderLineRequest(
                latte.id(), pcsId, null, null, List.of(oat, full), null, null)))
                .hasMessageContaining("at most 1 Milk");

        order = orders.addLine(orderUid, new AddRestaurantOrderLineRequest(
                latte.id(), pcsId, new BigDecimal("2"), "extra hot", List.of(oat, syrupOpt, noSugar), null, null));
        RestaurantOrderLineDto parent = lineFor(order, latte);
        assertThat(parent.note()).isEqualTo("Oat milk, No sugar — extra hot");
        RestaurantOrderLineDto child = lineFor(order, syrup);
        assertThat(child.parentLineUid()).isEqualTo(parent.uid());
        assertThat(child.quantity()).isEqualByComparingTo("2");
        assertThatThrownBy(() -> orders.deleteLine(orderUid, child.uid()))
                .hasMessageContaining("item this add-on belongs to");

        // Changing the item's quantity changes its add-on with it.
        order = orders.updateLine(orderUid, parent.uid(),
                new UpdateRestaurantOrderLineRequest(new BigDecimal("3"), parent.note()));
        assertThat(lineFor(order, syrup).quantity()).isEqualByComparingTo("3");

        RestaurantSendResultDto sent = orders.send(orderUid);
        assertThat(slip(sent.slips(), "Bar").lines()).extracting(PrepSlipDto.Line::productName, PrepSlipDto.Line::addOn)
                .containsExactly(tuple("Pumpkin Spice Latte", false), tuple("Syrup extra", true));

        RestaurantBillDto bill = orders.bill(orderUid, new BillRestaurantOrderRequest(customerId, "TZS"));
        assertThat(bill.lines()).extracting(RestaurantBillDto.Line::productName)
                .contains("Pumpkin Spice Latte", "Syrup extra");

        setRootCtx();
        RestaurantVoidResultDto voided = orders.voidLine(orderUid, parent.uid(),
                new VoidRestaurantOrderLineRequest("Wrong order", null, null));
        assertThat(voided.order().lines()).allSatisfy(l ->
                assertThat(l.status()).isEqualTo(RestaurantOrderLineStatus.VOIDED));
        assertThat(slip(voided.slips(), "Bar").lines()).hasSize(2);
    }

    @Test
    void courses_heldItemsWaitUntilFired() {
        setCashierCtx();
        RestaurantOrderDto order = orders.open(new CreateRestaurantOrderRequest(null, table1.uid(), null, null));
        orders.addLine(order.uid(), item(latte));
        orders.addLine(order.uid(), new AddRestaurantOrderLineRequest(
                tacos.id(), pcsId, null, null, null, (short) 2, true));

        RestaurantSendResultDto first = orders.send(order.uid());
        assertThat(first.slips()).extracting(PrepSlipDto::stationName).containsExactly("Bar");
        assertThat(lineFor(first.order(), tacos).status()).isEqualTo(RestaurantOrderLineStatus.PENDING);
        assertThat(first.order().pendingLineCount()).as("a held item is not waiting to be sent").isZero();

        RestaurantSendResultDto mains = orders.fire(order.uid(), new FireRestaurantOrderRequest((short) 2));
        assertThat(mains.slips()).singleElement().satisfies(s -> {
            assertThat(s.stationName()).isEqualTo("Kitchen");
            assertThat(s.lines().get(0).course()).isEqualTo(2);
        });
        assertThat(lineFor(mains.order(), tacos).held()).isFalse();
    }

    @Test
    void kitchenDisplay_showsTheStationsTickets_bumpMarksReady_andTheFloorSaysFoodReady() {
        setCashierCtx();
        RestaurantOrderDto order = openWith(tacos);
        orders.send(order.uid());
        String tacoLine = lineFor(orders.get(order.uid()), tacos).uid();

        setRootCtx();
        var tickets = kitchenDisplay.tickets(kitchen.uid());
        assertThat(tickets).singleElement().satisfies(t -> {
            assertThat(t.tableName()).isEqualTo("T1");
            assertThat(t.items()).extracting(KitchenTicketDto.Item::productName)
                    .containsExactly("Grilled Chicken Tacos");
        });
        assertThat(kitchenDisplay.tickets(bar.uid())).as("nothing for the bar").isEmpty();

        kitchenDisplay.ready(List.of(tacoLine));
        assertThat(kitchenDisplay.tickets(kitchen.uid())).isEmpty();
        assertThat(lineFor(orders.get(order.uid()), tacos).readyAt()).isNotNull();
        assertThat(setup.listTables(null).stream().filter(t -> t.uid().equals(table1.uid()))
                .findFirst().orElseThrow().readyItemCount()).isEqualTo(1);

        kitchenDisplay.recall(List.of(tacoLine));
        assertThat(kitchenDisplay.tickets(kitchen.uid())).hasSize(1);
    }

    @Test
    void waiterAndKitchenRoles_areSeededWithTheirBundles() {
        List<String> waiter = jdbc.queryForList("""
                SELECT p.code FROM role_permission rp
                JOIN roles r ON r.id = rp.role_id JOIN permissions p ON p.id = rp.permission_id
                WHERE r.code = 'WAITER' AND r.organisation_id IS NULL""", String.class);
        assertThat(waiter).contains("RESTAURANT.ORDER.MANAGE", "PRODUCT.VIEW")
                .doesNotContain("POS.SALE.CREATE", "POS.SESSION.OPEN", "RESTAURANT.ORDER.VOID");
        List<String> kitchenRole = jdbc.queryForList("""
                SELECT p.code FROM role_permission rp
                JOIN roles r ON r.id = rp.role_id JOIN permissions p ON p.id = rp.permission_id
                WHERE r.code = 'KITCHEN' AND r.organisation_id IS NULL""", String.class);
        assertThat(kitchenRole).contains("RESTAURANT.KITCHEN.MANAGE")
                .doesNotContain("RESTAURANT.ORDER.MANAGE", "POS.SALE.CREATE");
    }

    // =========================================================================
    // Any set-up works: waiters on a shared till, USB printers on a kitchen PC, items without a
    // category given one from the routing screen.
    // =========================================================================

    @Test
    void aWaiterSignedInOnTheSharedTill_isRecordedAsTheServer_andNamedOnTheSlip() {
        setRootCtx();
        AppUser waiter = new AppUser("resto_waiter", passwordEncoder.encode("W@iter1Pass"), "Baraka Waiter");
        waiter.setOrganisationId(orgId);
        waiter = users.save(waiter);
        userBranches.save(new UserBranch(waiter.getId(), branch, rootId));
        grantRole(waiter.getId(), "WAITER");
        String waiterUid = waiter.getUid();

        setCashierCtx();  // the cashier's till, the waiter's order
        RestaurantOrderDto order = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), null, null, waiterUid));
        assertThat(order.openedByName()).isEqualTo("Baraka Waiter");
        orders.addLine(order.uid(), item(tacos));
        RestaurantSendResultDto sent = orders.send(order.uid(), waiterUid);
        assertThat(sent.slips().get(0).byName()).isEqualTo("Baraka Waiter");

        assertThatThrownBy(() -> orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), null, null, "01NOTAREALUSER0000000000XX")))
                .as("a waiter uid is re-verified, never trusted")
                .isInstanceOf(ForbiddenException.class);
    }

    @Test
    void stationFeed_givesAKitchenPcEverySlipOnce_andVoidsAfterThem() {
        setCashierCtx();
        var start = kitchenFeed(null);
        assertThat(start.slips()).as("a device switched on prints no history").isEmpty();

        RestaurantOrderDto order = openWith(tacos);
        orders.send(order.uid());
        var first = kitchenFeed(start.until());
        assertThat(first.slips()).singleElement().satisfies(s -> {
            assertThat(s.kind()).isEqualTo(PrepSlipKind.FIRE);
            assertThat(s.stationName()).isEqualTo("Kitchen");
            assertThat(s.lineUids()).hasSize(1);
        });
        assertThat(kitchenFeed(first.until()).slips()).as("nothing new").isEmpty();

        setRootCtx();
        String line = lineFor(orders.get(order.uid()), tacos).uid();
        orders.voidLine(order.uid(), line, new VoidRestaurantOrderLineRequest("Changed mind", null, null));
        assertThat(kitchenFeed(first.until()).slips()).singleElement()
                .satisfies(s -> assertThat(s.kind()).isEqualTo(PrepSlipKind.VOID));
    }

    @Test
    void anItemWithoutACategory_canBeGivenOne_andThenRoutesToTheBar() {
        setRootCtx();
        ProductDto juice = menuItem("Passion Juice", "4000", null);
        productService.setCategory(juice.uid(), "  drinks ");
        assertThat(productService.getByUid(juice.uid()).category()).isEqualTo("drinks");

        setCashierCtx();
        RestaurantOrderDto order = openWith(juice);
        assertThat(orders.send(order.uid()).slips()).extracting(PrepSlipDto::stationName)
                .containsExactly("Bar");
    }

    // =========================================================================
    // Tulia 2026-11-12: the product form chooses where a department prints.
    // =========================================================================

    @Test
    void aNewDepartment_routedFromTheProductForm_printsThere() {
        setRootCtx();
        ProductDto juice = menuItem("Mango Juice", "4000", "Juices");

        setCashierCtx();
        RestaurantOrderDto before = openWith(juice);
        assertThat(orders.send(before.uid()).slips()).extracting(PrepSlipDto::stationName)
                .as("an unrouted department goes to the default station")
                .containsExactly("Kitchen");

        // A product manager without restaurant set-up rights may route a NEW department.
        PrepStationCategoryDto route = setup.setCategoryRoute(
                new SetCategoryRouteRequest(null, "  Juices ", bar.uid()));
        assertThat(route.category()).isEqualTo("Juices");
        assertThat(route.prepStationName()).isEqualTo("Bar");
        assertThat(setup.listCategoryRoutes(null)).extracting(PrepStationCategoryDto::category)
                .as("the other routes are left alone")
                .containsExactlyInAnyOrder("drinks", "Juices");

        setRootCtx();
        ProductDto passion = menuItem("Passion Juice", "4000", "juices");
        setCashierCtx();
        orders.addLine(before.uid(), item(passion));
        assertThat(orders.send(before.uid()).slips()).extracting(PrepSlipDto::stationName)
                .as("matched case-insensitively")
                .containsExactly("Bar");
    }

    @Test
    void movingADepartmentThatAlreadyPrints_needsRestaurantSetUp() {
        setCashierCtx();
        assertThatThrownBy(() -> setup.setCategoryRoute(
                new SetCategoryRouteRequest(null, "DRINKS", kitchen.uid())))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("already prints at Bar");
        // Choosing the station it already prints at is fine.
        assertThat(setup.setCategoryRoute(new SetCategoryRouteRequest(null, "Drinks", bar.uid()))
                .prepStationName()).isEqualTo("Bar");

        setRootCtx();
        assertThat(setup.setCategoryRoute(new SetCategoryRouteRequest(null, "Drinks", kitchen.uid()))
                .prepStationName()).isEqualTo("Kitchen");
        assertThat(setup.listCategoryRoutes(null)).singleElement()
                .satisfies(r -> assertThat(r.prepStationName()).isEqualTo("Kitchen"));
    }

    @Test
    void aDepartmentCannotBeRoutedToAStationOfAnotherBranch() {
        setRootCtx();
        Branch other = branches.save(new Branch(company, "RS3", "Third Branch"));
        RequestContext.set(new RequestContext.Principal(
                rootId, "resto_root", true, company.getId(), other.getId(), null, orgId));
        PrepStationDto foreign = setup.createStation(
                new CreatePrepStationRequest(null, "Grill", "192.168.1.60", null, false));

        setRootCtx();
        assertThatThrownBy(() -> setup.setCategoryRoute(
                new SetCategoryRouteRequest(null, "Grill", foreign.uid())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("station of this branch");
    }

    // =========================================================================
    // J COB 2026-10-30: waiter tiles, and the reports per waiter.
    // =========================================================================

    @Test
    void waiterTilesAndReports_followTheWaiterWhoOpenedEachOrder() {
        setRootCtx();
        AppUser waiter = new AppUser("resto_waiter2", passwordEncoder.encode("W@iter1Pass"), "Baraka Waiter");
        waiter.setOrganisationId(orgId);
        waiter = users.save(waiter);
        userBranches.save(new UserBranch(waiter.getId(), branch, rootId));
        grantRole(waiter.getId(), "WAITER");
        String waiterUid = waiter.getUid();

        assertThat(setup.listWaiters(null))
                .as("only WAITER-role users of the branch get a tile; the cashier is not one")
                .singleElement().satisfies(w -> {
                    assertThat(w.uid()).isEqualTo(waiterUid);
                    assertThat(w.name()).isEqualTo("Baraka Waiter");
                    assertThat(w.username()).isEqualTo("resto_waiter2");
                });

        // The waiter's table: billed and paid at the cashier's till.
        setCashierCtx();
        RestaurantOrderDto served = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), (short) 2, null, waiterUid));
        orders.addLine(served.uid(), item(tacos));
        RestaurantBillDto bill = orders.bill(served.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos);
        setCashierCtx();
        orders.pay(served.uid(), "resto-report-pay", payment(bill.grossTotal()));

        // The cashier's own order, still open.
        RestaurantOrderDto pending = openWith(latte);

        setRootCtx();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
        var sales = reports.waiterSales(company.getId(), today, today, null, null);
        assertThat(sales.rows()).singleElement().satisfies(r -> {
            assertThat(r.waiterName()).isEqualTo("Baraka Waiter");
            assertThat(r.bills()).isEqualTo(1);
            assertThat(r.guests()).isEqualTo(2);
            assertThat(r.amount()).isEqualByComparingTo(bill.grossTotal());
        });
        assertThat(sales.totals().amount()).isEqualByComparingTo(bill.grossTotal());

        var bills = reports.bills(company.getId(), today, today, waiterUid, null);
        assertThat(bills.waiterName()).isEqualTo("Baraka Waiter");
        assertThat(bills.rows()).singleElement().satisfies(r -> {
            assertThat(r.orderNumber()).isEqualTo(served.orderNumber());
            assertThat(r.servedAt()).isEqualTo("T1");
            assertThat(r.cashierName()).as("the till session's cashier").isEqualTo("Asha Waiter");
            assertThat(r.invoiceNumber()).isNotBlank();
            assertThat(r.invoiceStatus()).isEqualTo("FINALISED");
        });
        assertThat(bills.totalAmount()).isEqualByComparingTo(bill.grossTotal());

        var open = reports.pendingBills(company.getId(), null, null);
        assertThat(open.rows()).singleElement().satisfies(r -> {
            assertThat(r.orderUid()).isEqualTo(pending.uid());
            assertThat(r.waiterName()).isEqualTo("Asha Waiter");
            assertThat(r.status()).isEqualTo("OPEN");
            assertThat(r.items()).isEqualByComparingTo("1");
            assertThat(r.billedAmount()).isNull();
        });
        assertThat(reports.pendingBills(company.getId(), waiterUid, null).rows())
                .as("filtered to the waiter, whose only order is paid").isEmpty();
    }

    @Test
    void dashboardAndInsightReports_addUpToTheSalesTheyCover() {
        setRootCtx();
        String juma = waiter("resto_juma", "Juma Waiter");

        // Juma's table of three: tacos and a latte, billed and paid.
        setCashierCtx();
        RestaurantOrderDto served = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), (short) 3, null, juma));
        orders.addLine(served.uid(), item(tacos));
        orders.addLine(served.uid(), item(latte));
        RestaurantBillDto bill = orders.bill(served.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos, latte);
        setCashierCtx();
        orders.pay(served.uid(), "resto-insights-pay", payment(bill.grossTotal()));
        BigDecimal gross = bill.grossTotal();

        // A table cancelled before anything was sent, and one still open with one latte voided.
        orders.cancel(openWith(cake).uid(), null);
        RestaurantOrderDto open = orders.open(new CreateRestaurantOrderRequest(null, table1.uid(), null, null));
        orders.addLine(open.uid(), new AddRestaurantOrderLineRequest(latte.id(), pcsId, new BigDecimal("2"), null));
        orders.send(open.uid());
        setRootCtx();
        orders.voidLine(open.uid(), orders.get(open.uid()).lines().get(0).uid(),
                new VoidRestaurantOrderLineRequest("Spilt", BigDecimal.ONE, null));

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
        Long co = company.getId();

        var d = insights.dashboard(co, today, today, null);
        assertThat(d.current().sales()).isEqualByComparingTo(gross);
        assertThat(d.current().receipts()).isEqualTo(1);
        assertThat(d.current().orders()).isEqualTo(1);
        assertThat(d.current().guests()).isEqualTo(3);
        assertThat(d.current().spendPerGuest()).isEqualByComparingTo(
                gross.divide(BigDecimal.valueOf(3), 2, java.math.RoundingMode.HALF_EVEN));
        assertThat(d.current().cancelledOrders()).isEqualTo(1);
        assertThat(d.current().voidedItems()).isEqualByComparingTo("1");
        assertThat(d.current().voidedReceipts()).isZero();
        assertThat(d.previous().sales()).as("nothing sold yesterday").isEqualByComparingTo("0");
        assertThat(d.live().openOrders()).isEqualTo(1);
        assertThat(d.days()).singleElement().satisfies(x -> assertThat(x.sales()).isEqualByComparingTo(gross));
        assertThat(d.hours()).hasSize(24);
        assertThat(d.hours().stream().map(RestaurantDashboardDto.Hour::sales)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(gross);
        assertThat(d.weekdays()).hasSize(7);
        assertThat(d.weekdays().get(today.getDayOfWeek().getValue() - 1).days()).isEqualTo(1);
        assertThat(d.departments()).extracting(RestaurantDashboardDto.Department::name)
                .containsExactlyInAnyOrder("Food", "Drinks");
        assertThat(d.departments().stream().map(RestaurantDashboardDto.Department::sales)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(gross);
        assertThat(d.topItems()).hasSize(2);
        assertThat(d.waiters()).as("every receipt came from a table order: no counter row")
                .singleElement().satisfies(w -> assertThat(w.waiterName()).isEqualTo("Juma Waiter"));
        assertThat(d.tenders().stream().map(RestaurantDashboardDto.Tender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(gross);

        var hours = insights.daypart(co, today, today, null);
        assertThat(hours.totals().sales()).isEqualByComparingTo(gross);
        assertThat(hours.totals().receipts()).isEqualTo(1);
        assertThat(hours.departments()).containsExactlyInAnyOrder("Food", "Drinks");
        assertThat(hours.weekdays()).hasSize(7);

        var pay = insights.payments(co, today, today, null);
        assertThat(pay.totalPaid()).isEqualByComparingTo(gross);
        assertThat(pay.tenders()).singleElement()
                .satisfies(t -> assertThat(t.share()).isEqualByComparingTo("100"));
        assertThat(pay.completion().opened()).isEqualTo(3);
        assertThat(pay.completion().paid()).isEqualTo(1);
        assertThat(pay.completion().cancelled()).isEqualTo(1);
        assertThat(pay.completion().pending()).isEqualTo(1);
        assertThat(pay.cancelledOrders()).hasSize(1);
        assertThat(pay.voidedItems()).singleElement().satisfies(v -> {
            assertThat(v.reason()).isEqualTo("Spilt");
            assertThat(v.quantity()).isEqualByComparingTo("1");
            assertThat(v.productName()).isEqualTo(latte.name());
        });

        var items = reports.waiterItems(co, today, today, null, null);
        assertThat(items.rows()).hasSize(2);
        assertThat(items.waiters()).singleElement().satisfies(w -> {
            assertThat(w.waiterName()).isEqualTo("Juma Waiter");
            assertThat(w.bills()).isEqualTo(1);
        });
        assertThat(items.totalAmount()).isEqualByComparingTo(gross);

        var summary = reports.billsSummary(co, today, today, null, null);
        assertThat(summary.rows()).singleElement().satisfies(r -> {
            assertThat(r.bills()).isEqualTo(1);
            assertThat(r.guests()).isEqualTo(3);
            assertThat(r.amount()).isEqualByComparingTo(gross);
            assertThat(r.tenders().stream().reduce(BigDecimal.ZERO, BigDecimal::add))
                    .isEqualByComparingTo(gross);
        });
        assertThat(summary.tenderTypes()).hasSize(1);

        var pending = reports.pendingSummary(co, null, null);
        assertThat(pending.rows()).singleElement().satisfies(r -> {
            assertThat(r.waiterName()).isEqualTo("Asha Waiter");
            assertThat(r.open()).isEqualTo(1);
            assertThat(r.billed()).isZero();
            assertThat(r.items()).as("two lattes, one voided").isEqualByComparingTo("1");
            assertThat(r.oldestOpenedAt()).isNotBlank();
        });
        assertThat(reports.pendingSummary(co, juma, null).rows()).isEmpty();
    }

    @Test
    void itemAndDepartmentFilters_narrowTheReports_billsWholeAndLinesMatching() {
        setRootCtx();
        String juma = waiter("resto_juma2", "Juma Waiter");

        // Juma: tacos (Food) and a latte (Drinks), paid. Asha: a cake (no department), paid.
        setCashierCtx();
        RestaurantOrderDto mixed = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), (short) 3, null, juma));
        orders.addLine(mixed.uid(), item(tacos));
        orders.addLine(mixed.uid(), item(latte));
        RestaurantBillDto mixedBill = orders.bill(mixed.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos, latte, cake);
        setCashierCtx();
        orders.pay(mixed.uid(), "resto-filter-pay-1", payment(mixedBill.grossTotal()));
        RestaurantOrderDto cakeOrder = openWith(cake);
        RestaurantBillDto cakeBill = orders.bill(cakeOrder.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        orders.pay(cakeOrder.uid(), "resto-filter-pay-2", payment(cakeBill.grossTotal()));

        // Still open: two lattes sent, one voided.
        RestaurantOrderDto open = orders.open(new CreateRestaurantOrderRequest(null, table1.uid(), null, null));
        orders.addLine(open.uid(), new AddRestaurantOrderLineRequest(latte.id(), pcsId, new BigDecimal("2"), null));
        orders.send(open.uid());
        setRootCtx();
        orders.voidLine(open.uid(), orders.get(open.uid()).lines().get(0).uid(),
                new VoidRestaurantOrderLineRequest("Spilt", BigDecimal.ONE, null));

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
        Long co = company.getId();
        var all = insights.dashboard(co, today, today, null);
        BigDecimal drinks = deptSales(all, "Drinks");
        BigDecimal food = deptSales(all, "Food");
        BigDecimal none = deptSales(all, "(no department)");
        var tacosOnly = new RestaurantReportQuery.ItemFilter(tacos.uid(), null);
        var latteOnly = new RestaurantReportQuery.ItemFilter(latte.uid(), null);
        var drinksDept = new RestaurantReportQuery.ItemFilter(null, "  dRINKS ");
        var noDept = new RestaurantReportQuery.ItemFilter(null, "(no department)");

        // Bill grain: the bills holding a match, each at its whole amount.
        assertThat(reports.waiterSales(co, today, today, null, null, tacosOnly).rows())
                .singleElement().satisfies(r -> {
                    assertThat(r.waiterName()).isEqualTo("Juma Waiter");
                    assertThat(r.bills()).isEqualTo(1);
                    assertThat(r.guests()).isEqualTo(3);
                    assertThat(r.amount()).isEqualByComparingTo(mixedBill.grossTotal());
                });
        assertThat(reports.waiterSales(co, today, today, null, null, noDept).rows())
                .singleElement().satisfies(r -> {
                    assertThat(r.waiterName()).isEqualTo("Asha Waiter");
                    assertThat(r.amount()).isEqualByComparingTo(cakeBill.grossTotal());
                });
        assertThat(reports.bills(co, today, today, null, null, drinksDept).rows())
                .singleElement().satisfies(r -> assertThat(r.orderNumber()).isEqualTo(mixed.orderNumber()));
        assertThat(reports.bills(co, today, today, null, null,
                new RestaurantReportQuery.ItemFilter(tacos.uid(), "Drinks")).rows())
                .as("both filters at once: tacos are not drinks").isEmpty();
        assertThat(reports.billsSummary(co, today, today, null, null, noDept).rows())
                .singleElement().satisfies(r -> assertThat(r.amount()).isEqualByComparingTo(cakeBill.grossTotal()));

        // Line grain: only the matching lines.
        var items = reports.waiterItems(co, today, today, null, null, drinksDept);
        assertThat(items.rows()).singleElement().satisfies(r -> assertThat(r.name()).isEqualTo(latte.name()));
        assertThat(items.totalAmount()).isEqualByComparingTo(drinks);
        assertThat(items.waiters()).singleElement().satisfies(w -> assertThat(w.bills()).isEqualTo(1));

        // Pending: the orders holding a matching item that is not voided.
        assertThat(reports.pendingBills(co, null, null, latteOnly).rows())
                .singleElement().satisfies(r -> assertThat(r.orderUid()).isEqualTo(open.uid()));
        assertThat(reports.pendingBills(co, null, null, tacosOnly).rows()).isEmpty();
        assertThat(reports.pendingSummary(co, null, null, drinksDept).rows()).hasSize(1);

        // Dashboard: sales are the matching lines; receipts, orders and guests hold one.
        var d = insights.dashboard(co, today, today, null, drinksDept);
        assertThat(d.current().sales()).isEqualByComparingTo(drinks);
        assertThat(d.current().receipts()).isEqualTo(1);
        assertThat(d.current().orders()).isEqualTo(1);
        assertThat(d.current().guests()).isEqualTo(3);
        assertThat(d.current().voidedItems()).isEqualByComparingTo("1");
        assertThat(d.live().openOrders()).isEqualTo(1);
        assertThat(d.departments()).extracting(RestaurantDashboardDto.Department::name)
                .containsExactly("Drinks");
        assertThat(d.topItems()).singleElement().satisfies(i -> assertThat(i.name()).isEqualTo(latte.name()));
        assertThat(d.waiters()).singleElement().satisfies(w -> {
            assertThat(w.waiterName()).isEqualTo("Juma Waiter");
            assertThat(w.amount()).as("the drinks on Juma's bill, not the whole bill")
                    .isEqualByComparingTo(drinks);
        });
        assertThat(d.days()).singleElement().satisfies(x -> assertThat(x.sales()).isEqualByComparingTo(drinks));
        assertThat(d.tenders().stream().map(RestaurantDashboardDto.Tender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add))
                .as("payments are whole: the receipt that had drinks").isEqualByComparingTo(mixedBill.grossTotal());

        var food2 = insights.dashboard(co, today, today, null, new RestaurantReportQuery.ItemFilter(null, "food"));
        assertThat(food2.current().sales()).isEqualByComparingTo(food);
        assertThat(food2.current().voidedItems()).isEqualByComparingTo("0");
        assertThat(food2.live().openOrders()).isZero();
        assertThat(insights.dashboard(co, today, today, null, noDept).current().sales())
                .isEqualByComparingTo(none);

        // Hours: sales by line, receipts holding one.
        var hours = insights.daypart(co, today, today, null, tacosOnly);
        assertThat(hours.totals().sales()).isEqualByComparingTo(food);
        assertThat(hours.totals().receipts()).isEqualTo(1);
        assertThat(hours.departments()).containsExactly("Food");

        // Payments: only the voided items narrow.
        var pay = insights.payments(co, today, today, null, tacosOnly);
        assertThat(pay.voidedItems()).isEmpty();
        assertThat(pay.totalPaid()).isEqualByComparingTo(mixedBill.grossTotal().add(cakeBill.grossTotal()));
        assertThat(insights.payments(co, today, today, null, latteOnly).voidedItems()).hasSize(1);

        // An item that is not this company's is "not found", whatever the uid.
        assertThatThrownBy(() -> reports.waiterSales(co, today, today, null, null,
                new RestaurantReportQuery.ItemFilter("01NOTAREALPRODUCT000000000", null)))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class)
                .hasMessage("Item not found.");
        assertThat(reports.itemFilterLines(co, new RestaurantReportQuery.ItemFilter(tacos.uid(), " Food ")))
                .containsExactly("Item: " + tacos.name(), "Department: Food");
    }

    /**
     * ADR-0072 follow-up: a POS item return on a paid restaurant bill is netted by the dashboard,
     * hours, payments and the waiter reports - line grain by return date (and item filter), bill
     * grain as the bill net of its returns - and the waiters still add up to the sales.
     */
    @Test
    void anItemReturnOnAPaidBill_isNettedByTheRestaurantReportsAndInsights() {
        setRootCtx();
        String juma = waiter("resto_juma4", "Juma Waiter");
        setCashierCtx();
        RestaurantOrderDto served = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), (short) 3, null, juma));
        orders.addLine(served.uid(), item(tacos));
        orders.addLine(served.uid(), item(latte));
        RestaurantBillDto bill = orders.bill(served.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos, latte);
        setCashierCtx();
        orders.pay(served.uid(), "resto-return-pay", payment(bill.grossTotal()));
        BigDecimal gross = bill.grossTotal();

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
        Long co = company.getId();
        BigDecimal drinksBefore = deptSales(insights.dashboard(co, today, today, null), "Drinks");

        // Return the latte: the only line in the Drinks department.
        setRootCtx();
        String invoiceUid = orders.get(served.uid()).salesInvoiceUid();
        String latteLineUid = posReturns.listForSale(invoiceUid).lines().stream()
                .filter(l -> l.productName().equals(latte.name())).findFirst().orElseThrow().invoiceLineUid();
        var ret = posReturns.returnItems(invoiceUid, null, new PosSaleReturnRequest("cold",
                null, List.of(new PosSaleReturnRequest.LineDto(latteLineUid, BigDecimal.ONE))));
        BigDecimal back = ret.grossAmount();
        assertThat(back).isEqualByComparingTo(drinksBefore);
        BigDecimal net = gross.subtract(back);

        var d = insights.dashboard(co, today, today, null);
        assertThat(d.current().sales()).isEqualByComparingTo(net);
        assertThat(d.current().receipts()).as("a returned bill was still a bill").isEqualTo(1);
        assertThat(d.current().spendPerGuest()).isEqualByComparingTo(
                net.divide(BigDecimal.valueOf(3), 2, java.math.RoundingMode.HALF_EVEN));
        assertThat(d.current().refunds()).as("the item return is a REFUND payout").isEqualByComparingTo(back);
        assertThat(d.days()).singleElement().satisfies(x -> assertThat(x.sales()).isEqualByComparingTo(net));
        assertThat(d.hours().stream().map(RestaurantDashboardDto.Hour::sales)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(net);
        assertThat(d.departments().stream().map(RestaurantDashboardDto.Department::sales)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(net);
        assertThat(deptSales(d, "Drinks")).isEqualByComparingTo("0");
        assertThat(d.topItems().stream().filter(i -> i.name().equals(latte.name())).findFirst().orElseThrow()
                .quantity()).isEqualByComparingTo("0");
        assertThat(d.topItems().get(0).name()).as("re-ranked: tacos lead once the latte is gone")
                .isEqualTo(tacos.name());
        assertThat(d.waiters()).singleElement().satisfies(w -> assertThat(w.amount()).isEqualByComparingTo(net));
        assertThat(d.tenders().stream().map(RestaurantDashboardDto.Tender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).as("cash refunded comes off the cash line")
                .isEqualByComparingTo(net);

        var drinks = insights.dashboard(co, today, today, null,
                new RestaurantReportQuery.ItemFilter(null, "Drinks"));
        assertThat(drinks.current().sales()).as("the filter reaches the returned lines").isEqualByComparingTo("0");
        assertThat(insights.dashboard(co, today, today, null,
                new RestaurantReportQuery.ItemFilter(tacos.uid(), null)).current().sales())
                .isEqualByComparingTo(gross.subtract(drinksBefore));

        assertThat(insights.daypart(co, today, today, null).totals().sales()).isEqualByComparingTo(net);
        var pay = insights.payments(co, today, today, null);
        assertThat(pay.totalPaid()).isEqualByComparingTo(net);
        assertThat(pay.refunds()).singleElement().satisfies(r -> {
            assertThat(r.amount()).isEqualByComparingTo(back);
            assertThat(r.reason()).startsWith("Item return PRT-");
        });

        // Bill grain: the bill net of its returns, the returned part shown.
        assertThat(reports.waiterSales(co, today, today, null, null).rows()).singleElement().satisfies(r -> {
            assertThat(r.bills()).isEqualTo(1);
            assertThat(r.amount()).isEqualByComparingTo(net);
        });
        assertThat(reports.bills(co, today, today, null, null).rows()).singleElement().satisfies(r -> {
            assertThat(r.amount()).isEqualByComparingTo(net);
            assertThat(r.returned()).isEqualByComparingTo(back);
        });
        assertThat(reports.billsSummary(co, today, today, null, null).rows()).singleElement().satisfies(r -> {
            assertThat(r.amount()).isEqualByComparingTo(net);
            assertThat(r.tenders().stream().reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(net);
        });
        // Line grain.
        var items = reports.waiterItems(co, today, today, null, null);
        assertThat(items.totalAmount()).isEqualByComparingTo(net);
        assertThat(items.rows().stream().filter(r -> r.name().equals(latte.name())).findFirst().orElseThrow()
                .quantity()).isEqualByComparingTo("0");
    }

    @Test
    void waiterFilter_onDashboardHoursAndPayments_followsTheWaiterHoldingTheOrder() {
        setRootCtx();
        String juma = waiter("resto_juma3", "Juma Waiter");
        String asha = users.findById(cashierId).orElseThrow().getUid();

        // Juma: a paid table of two (tacos). Asha: a paid latte. A counter sale (cake), no order.
        setCashierCtx();
        RestaurantOrderDto jumaPaid = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), (short) 2, null, juma));
        orders.addLine(jumaPaid.uid(), item(tacos));
        RestaurantBillDto jumaBill = orders.bill(jumaPaid.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos, latte, cake);
        setCashierCtx();
        orders.pay(jumaPaid.uid(), "resto-waiter-pay-1", payment(jumaBill.grossTotal()));
        RestaurantOrderDto ashaPaid = openWith(latte);
        RestaurantBillDto ashaBill = orders.bill(ashaPaid.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        orders.pay(ashaPaid.uid(), "resto-waiter-pay-2", payment(ashaBill.grossTotal()));
        BigDecimal counter = posSales.processSale(null, new PosSaleRequest(sessionUid, customerId, null, "TZS",
                List.of(new PosSaleRequest.LineItem(cake.id(), pcsId, BigDecimal.ONE, null, null)),
                null, null)).grossTotalAmount();

        // Juma also cancels a table, and has one open with a latte voided of two.
        RestaurantOrderDto cancelled = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), null, null, juma));
        orders.addLine(cancelled.uid(), item(cake));
        orders.cancel(cancelled.uid(), null);
        RestaurantOrderDto open = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), null, null, juma));
        orders.addLine(open.uid(), new AddRestaurantOrderLineRequest(latte.id(), pcsId, new BigDecimal("2"), null));
        orders.send(open.uid());
        setRootCtx();
        orders.voidLine(open.uid(), orders.get(open.uid()).lines().get(0).uid(),
                new VoidRestaurantOrderLineRequest("Spilt", BigDecimal.ONE, null));

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
        Long co = company.getId();
        var none = RestaurantReportQuery.ItemFilter.NONE;
        BigDecimal all = jumaBill.grossTotal().add(ashaBill.grossTotal()).add(counter);

        var everyone = insights.dashboard(co, today, today, null, null, none);
        assertThat(everyone.current().sales()).isEqualByComparingTo(all);
        assertThat(everyone.waiters()).as("the counter sale has its own row")
                .anySatisfy(w -> assertThat(w.waiterUid()).isNull());

        // One waiter: their receipts, orders and items only; the counter sale has no waiter.
        var d = insights.dashboard(co, today, today, juma, null, none);
        assertThat(d.current().sales()).isEqualByComparingTo(jumaBill.grossTotal());
        assertThat(d.current().receipts()).isEqualTo(1);
        assertThat(d.current().orders()).isEqualTo(1);
        assertThat(d.current().guests()).isEqualTo(2);
        assertThat(d.current().cancelledOrders()).isEqualTo(1);
        assertThat(d.current().voidedItems()).isEqualByComparingTo("1");
        assertThat(d.current().refunds()).isEqualByComparingTo("0");
        assertThat(d.live().openOrders()).isEqualTo(1);
        assertThat(d.waiters()).as("no counter row for one waiter").singleElement()
                .satisfies(w -> assertThat(w.waiterName()).isEqualTo("Juma Waiter"));
        assertThat(d.tenders().stream().map(RestaurantDashboardDto.Tender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(jumaBill.grossTotal());
        assertThat(d.departments()).extracting(RestaurantDashboardDto.Department::name)
                .containsExactly("Food");

        var a = insights.dashboard(co, today, today, asha, null, none);
        assertThat(a.current().sales()).isEqualByComparingTo(ashaBill.grossTotal());
        assertThat(a.current().cancelledOrders()).isZero();
        assertThat(a.live().openOrders()).isZero();

        // With the department too (AND): Juma sold no drinks, but voided one and has one open.
        var jd = insights.dashboard(co, today, today, juma, null,
                new RestaurantReportQuery.ItemFilter(null, "Drinks"));
        assertThat(jd.current().sales()).isEqualByComparingTo("0");
        assertThat(jd.current().receipts()).isZero();
        assertThat(jd.current().voidedItems()).isEqualByComparingTo("1");
        assertThat(jd.live().openOrders()).isEqualTo(1);

        var hours = insights.daypart(co, today, today, juma, null, none);
        assertThat(hours.totals().sales()).isEqualByComparingTo(jumaBill.grossTotal());
        assertThat(hours.totals().receipts()).isEqualTo(1);
        assertThat(insights.daypart(co, today, today, null, null, none).totals().receipts()).isEqualTo(3);

        var pay = insights.payments(co, today, today, juma, null, none);
        assertThat(pay.totalPaid()).isEqualByComparingTo(jumaBill.grossTotal());
        assertThat(pay.completion().opened()).isEqualTo(3);
        assertThat(pay.completion().paid()).isEqualTo(1);
        assertThat(pay.completion().cancelled()).isEqualTo(1);
        assertThat(pay.completion().pending()).isEqualTo(1);
        assertThat(pay.cancelledOrders()).hasSize(1);
        assertThat(pay.voidedItems()).hasSize(1);
        assertThat(pay.voidedReceipts()).isEmpty();
        assertThat(pay.refunds()).isEmpty();
        assertThat(insights.payments(co, today, today, null, null, none).totalPaid())
                .isEqualByComparingTo(all);

        // Another company's user, or one with nothing to do with this one, is "not found".
        AppUser outsider = new AppUser("resto_outsider", passwordEncoder.encode("0utS1der!Pass"), "Out Sider");
        outsider.setOrganisationId(orgId);
        String outsiderUid = users.save(outsider).getUid();
        assertThatThrownBy(() -> insights.dashboard(co, today, today, outsiderUid, null, none))
                .isInstanceOf(com.erp.platform.common.api.NotFoundException.class)
                .hasMessage("Waiter not found.");
        assertThat(reports.waiterLines(co, juma)).containsExactly("Waiter: Juma Waiter");
    }

    /** ADR-0072 x the waiter filter: a return nets only on the waiter holding the returned bill. */
    @Test
    void waiterFilter_netsAnItemReturnOnThatWaitersBill_andLeavesTheOthersAlone() {
        setRootCtx();
        String juma = waiter("resto_juma5", "Juma Waiter");
        String asha = users.findById(cashierId).orElseThrow().getUid();
        setCashierCtx();
        RestaurantOrderDto jumaOrder = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), (short) 2, null, juma));
        orders.addLine(jumaOrder.uid(), item(tacos));
        orders.addLine(jumaOrder.uid(), item(latte));
        RestaurantBillDto jumaBill = orders.bill(jumaOrder.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos, latte);
        setCashierCtx();
        orders.pay(jumaOrder.uid(), "resto-wret-pay-1", payment(jumaBill.grossTotal()));
        RestaurantOrderDto ashaOrder = openWith(latte);
        RestaurantBillDto ashaBill = orders.bill(ashaOrder.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        orders.pay(ashaOrder.uid(), "resto-wret-pay-2", payment(ashaBill.grossTotal()));

        setRootCtx();
        String invoiceUid = orders.get(jumaOrder.uid()).salesInvoiceUid();
        String latteLine = posReturns.listForSale(invoiceUid).lines().stream()
                .filter(l -> l.productName().equals(latte.name())).findFirst().orElseThrow().invoiceLineUid();
        BigDecimal back = posReturns.returnItems(invoiceUid, null, new PosSaleReturnRequest("cold",
                null, List.of(new PosSaleReturnRequest.LineDto(latteLine, BigDecimal.ONE)))).grossAmount();

        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
        Long co = company.getId();
        var none = RestaurantReportQuery.ItemFilter.NONE;
        BigDecimal jumaNet = jumaBill.grossTotal().subtract(back);

        var j = insights.dashboard(co, today, today, juma, null, none);
        assertThat(j.current().sales()).isEqualByComparingTo(jumaNet);
        assertThat(j.current().receipts()).isEqualTo(1);
        assertThat(j.days()).singleElement().satisfies(x -> assertThat(x.sales()).isEqualByComparingTo(jumaNet));
        assertThat(j.departments().stream().map(RestaurantDashboardDto.Department::sales)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(jumaNet);
        assertThat(j.waiters()).singleElement().satisfies(w -> assertThat(w.amount()).isEqualByComparingTo(jumaNet));
        assertThat(j.tenders().stream().map(RestaurantDashboardDto.Tender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(jumaNet);
        assertThat(j.current().refunds()).isEqualByComparingTo(back);
        assertThat(insights.daypart(co, today, today, juma, null, none).totals().sales())
                .isEqualByComparingTo(jumaNet);
        var jp = insights.payments(co, today, today, juma, null, none);
        assertThat(jp.totalPaid()).isEqualByComparingTo(jumaNet);
        assertThat(jp.refunds()).hasSize(1);

        // Asha's bill had no return: her figures are whole, and no refund shows for her.
        var a = insights.dashboard(co, today, today, asha, null, none);
        assertThat(a.current().sales()).isEqualByComparingTo(ashaBill.grossTotal());
        assertThat(a.current().refunds()).isEqualByComparingTo("0");
        assertThat(a.tenders().stream().map(RestaurantDashboardDto.Tender::amount)
                .reduce(BigDecimal.ZERO, BigDecimal::add)).isEqualByComparingTo(ashaBill.grossTotal());
        assertThat(insights.daypart(co, today, today, asha, null, none).totals().sales())
                .isEqualByComparingTo(ashaBill.grossTotal());
        var ap = insights.payments(co, today, today, asha, null, none);
        assertThat(ap.totalPaid()).isEqualByComparingTo(ashaBill.grossTotal());
        assertThat(ap.refunds()).isEmpty();

        // With the item filter as well: Juma's drinks are all returned.
        assertThat(insights.dashboard(co, today, today, juma, null,
                new RestaurantReportQuery.ItemFilter(null, "Drinks")).current().sales())
                .isEqualByComparingTo("0");
        // Waiter-scoped report rows agree.
        assertThat(reports.waiterItems(co, today, today, juma, null).totalAmount()).isEqualByComparingTo(jumaNet);
    }

    private static BigDecimal deptSales(RestaurantDashboardDto d, String name) {
        return d.departments().stream().filter(x -> x.name().equals(name))
                .map(RestaurantDashboardDto.Department::sales).findFirst().orElseThrow();
    }

    // =========================================================================
    // ADR-0067: the waiter holding a table, and moving it to another waiter.
    // =========================================================================

    @Test
    void transfer_movesTheTableToAnotherWaiter_withAuthority_andTheReportsFollowIt() {
        setRootCtx();
        String baraka = waiter("resto_baraka", "Baraka Waiter");
        String neema  = waiter("resto_neema", "Neema Waiter");
        String rootUid = users.findByUsername("resto_root").orElseThrow().getUid();

        setCashierCtx();
        RestaurantOrderDto order = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), (short) 2, null, baraka));
        assertThat(order.waiterUid()).isEqualTo(baraka);
        assertThat(order.waiterName()).isEqualTo("Baraka Waiter");
        assertThat(order.waiterSince()).isNull();
        orders.addLine(order.uid(), item(tacos));

        // A waiter on the shared till may not take the table without a manager.
        assertThatThrownBy(() -> orders.transfer(order.uid(),
                new TransferRestaurantOrderRequest(neema, neema, null)))
                .isInstanceOf(ForbiddenException.class)
                .hasMessageContaining("manager's approval");

        // With a manager's approval at the till it moves; who opened it is kept.
        RestaurantOrderDto moved = orders.transfer(order.uid(),
                new TransferRestaurantOrderRequest(neema, neema, rootUid));
        assertThat(moved.waiterUid()).isEqualTo(neema);
        assertThat(moved.waiterName()).isEqualTo("Neema Waiter");
        assertThat(moved.waiterSince()).isNotNull();
        assertThat(moved.openedByName()).isEqualTo("Baraka Waiter");
        assertThat(orders.list(null, null, null).stream().filter(o -> o.uid().equals(order.uid())))
                .singleElement().satisfies(o -> assertThat(o.waiterName()).isEqualTo("Neema Waiter"));

        // A waiter signed in on their own takes a table over for themselves, with approval
        // (the step-up check refuses self-vouching, so the server must not use it on them).
        setWaiterCtx("resto_baraka");
        RestaurantOrderDto back = orders.transfer(order.uid(),
                new TransferRestaurantOrderRequest(baraka, baraka, rootUid));
        assertThat(back.waiterUid()).isEqualTo(baraka);
        setRootCtx();
        moved = orders.transfer(order.uid(), new TransferRestaurantOrderRequest(neema, null, null));
        assertThat(moved.waiterUid()).isEqualTo(neema);
        setCashierCtx();

        // Not a waiter of the branch, or the same waiter again: refused.
        assertThatThrownBy(() -> orders.transfer(order.uid(),
                new TransferRestaurantOrderRequest("01NOTAREALUSER0000000000XX", null, rootUid)))
                .isInstanceOf(IllegalArgumentException.class);
        setRootCtx();  // a manager acting on their own needs no approval
        assertThatThrownBy(() -> orders.transfer(order.uid(),
                new TransferRestaurantOrderRequest(neema, null, null)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already serving");

        // The bill and the waiter reports follow the waiter holding the table when it is paid.
        setCashierCtx();
        RestaurantBillDto bill = orders.bill(order.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        assertThat(bill.serverName()).isEqualTo("Neema Waiter");
        stock(tacos);
        setCashierCtx();
        orders.pay(order.uid(), "resto-transfer-pay", payment(bill.grossTotal()));

        setRootCtx();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
        assertThat(reports.waiterSales(company.getId(), today, today, null, null).rows())
                .singleElement().satisfies(r -> assertThat(r.waiterName()).isEqualTo("Neema Waiter"));
        assertThat(reports.bills(company.getId(), today, today, neema, null).rows()).hasSize(1);
        assertThat(reports.bills(company.getId(), today, today, baraka, null).rows()).isEmpty();
    }

    /** A user of this branch holding the WAITER role; returns their uid. */
    private String waiter(String username, String name) {
        AppUser w = new AppUser(username, passwordEncoder.encode("W@iter1Pass"), name);
        w.setOrganisationId(orgId);
        w = users.save(w);
        userBranches.save(new UserBranch(w.getId(), branch, rootId));
        grantRole(w.getId(), "WAITER");
        return w.getUid();
    }

    private StationSlipsDto kitchenFeed(java.time.Instant since) {
        return orders.stationSlips(kitchen.uid(), since);
    }

    private void grantRole(Long userId, String roleCode) {
        jdbc.update("""
                INSERT INTO user_role (uid, user_id, role_id, company_id, granted_at, granted_by, version)
                SELECT ?, ?, r.id, ?, now(), ?, 0 FROM roles r
                WHERE r.code = ? AND r.organisation_id IS NULL""",
                "UR-" + roleCode + "-" + userId, userId, company.getId(), rootId, roleCode);
    }

    // =========================================================================
    // helpers
    // =========================================================================

    private static String option(List<ModifierGroupDto> groups, String label) {
        return groups.stream().flatMap(g -> g.options().stream())
                .filter(o -> o.label().equals(label)).findFirst().orElseThrow().uid();
    }

    private BigDecimal onHand(ProductDto p) {
        return stockOnHandRepo.findByCompanyIdAndBranchIdAndProductId(company.getId(), branch.getId(), p.id())
                .map(StockOnHand::getQuantity).orElse(BigDecimal.ZERO);
    }

    private void dispatchPending(String eventType, String aggregateUid) {
        Long eventId = domainEventRepo.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> aggregateUid.equals(e.getAggregateUid()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .reduce((x, y) -> y)
                .map(DomainEvent::getId)
                .orElseThrow(() -> new AssertionError("No PENDING " + eventType));
        dispatcher.dispatchOne(eventId);
    }

    private ProductDto menuItem(String name, String price, String category) {
        ProductDto dto = productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null,
                category, null, null, null, null, null, null, null, null));
        productService.setPrice(dto.uid(), new SetProductPriceRequest(priceListUid, new MoneyDto(price, "TZS")));
        return productService.getByUid(dto.uid());
    }

    // =========================================================================
    // ADR-0073: branch menu + branch price.
    // =========================================================================

    @Test
    void branchMenu_refusesAnItemNotSoldHere_atOrderAndAtTheTill_butNeverBlocksPayingAnOpenTable() {
        setRootCtx();
        productService.setBranchMenu(tacos.uid(), new com.erp.modules.products.domain.dto
                .SetBranchMenuRequest(branch.getUid(), false, null, null));

        setCashierCtx();
        RestaurantOrderDto order = openWith(cake);
        assertThatThrownBy(() -> orders.addLine(order.uid(), item(tacos)))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Grilled Chicken Tacos is not sold at this branch");
        assertThatThrownBy(() -> posSales.processTillSale(null, new PosSaleRequest(sessionUid,
                customerId, null, "TZS",
                List.of(new PosSaleRequest.LineItem(tacos.id(), pcsId, BigDecimal.ONE, null, null)),
                null, null)))
                .isInstanceOf(PosSaleFlowException.class)
                .hasMessageContaining("Grilled Chicken Tacos is not sold at this branch")
                .extracting(ex -> ((PosSaleFlowException) ex).getErrorCode())
                .isEqualTo("NOT_ON_BRANCH_MENU");

        // The cake was ordered while it was on the menu; taking it off now must not trap the table.
        setRootCtx();
        productService.setBranchMenu(cake.uid(), new com.erp.modules.products.domain.dto
                .SetBranchMenuRequest(branch.getUid(), false, null, null));
        stock(cake);
        setCashierCtx();
        RestaurantBillDto bill = orders.bill(order.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        RestaurantPaymentDto paid = orders.pay(order.uid(), "resto-menu-pay", payment(bill.grossTotal()));
        assertThat(paid.order().status()).isEqualTo(RestaurantOrderStatus.PAID);
    }

    @Test
    void branchPrice_pricesTheBillAndThePostedSale() {
        setRootCtx();
        productService.setBranchMenu(latte.uid(), new com.erp.modules.products.domain.dto
                .SetBranchMenuRequest(branch.getUid(), null, new BigDecimal("6500"), null));

        setCashierCtx();
        RestaurantOrderDto order = openWith(latte);
        RestaurantBillDto bill = orders.bill(order.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        assertThat(bill.lines()).singleElement()
                .as("the retail list is VAT-exclusive, so the bill shows 6,500 + 18% VAT")
                .satisfies(l -> assertThat(l.unitPrice()).isEqualByComparingTo("7670"));

        stock(latte);
        setCashierCtx();
        RestaurantPaymentDto paid = orders.pay(order.uid(), "resto-branch-price", payment(bill.grossTotal()));
        assertThat(paid.invoice().grossTotalAmount()).isEqualByComparingTo(bill.grossTotal());
        assertThat(jdbc.queryForObject(
                "SELECT l.list_price_amount FROM sales_invoice_lines l"
                        + " JOIN sales_invoices i ON i.id = l.invoice_id WHERE i.uid = ?",
                BigDecimal.class, paid.invoice().uid()))
                .as("the posted line's list price is the branch price")
                .isEqualByComparingTo("6500");
    }

    // =========================================================================
    // Cafe Tulia 2026-11-26: the daily sales log (items + customer on each bill), the category on
    // the sales report, and the Z-read's sales sections — all read-only, all tying out to the shift.
    // =========================================================================

    @Test
    void zReadSalesSections_billsLog_andSalesCategory_tieOutToTheShift() {
        setCashierCtx();
        RestaurantOrderDto a = orders.open(new CreateRestaurantOrderRequest(
                null, table1.uid(), (short) 2, null, null, "Mr Juma"));
        orders.addLine(a.uid(), item(tacos));
        orders.addLine(a.uid(), item(tacos));          // one line of 2
        orders.addLine(a.uid(), item(latte));
        orders.send(a.uid());                           // tacos → Kitchen, latte → Bar
        orders.bill(a.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        stock(tacos, latte, cake);
        setCashierCtx();
        // 10% off the whole bill: the summary must show it, and every group still sums to net.
        orders.pay(a.uid(), "zread-sections-a", new PayRestaurantOrderRequest(sessionUid, customerId,
                "TZS", null, null, null, null, null, new BigDecimal("10"), null, null));

        RestaurantOrderDto b = openWith(cake);          // uncategorised → the default station
        orders.send(b.uid());
        RestaurantBillDto billB = orders.bill(b.uid(), new BillRestaurantOrderRequest(customerId, "TZS"));
        setCashierCtx();
        orders.pay(b.uid(), "zread-sections-b", payment(billB.grossTotal()));

        // The daily sales log: what each bill bought, and whose it was.
        setRootCtx();
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneId.of("Africa/Dar_es_Salaam"));
        var bills = reports.bills(company.getId(), today, today, null, null).rows();
        assertThat(bills).hasSize(2);
        assertThat(bills).anySatisfy(r -> {
            assertThat(r.items()).contains("2× Grilled Chicken Tacos").contains("Pumpkin Spice Latte");
            assertThat(r.customerName()).isEqualTo("Mr Juma");
        });
        assertThat(bills).anySatisfy(r -> {
            assertThat(r.items()).isEqualTo("Brownie Cake");
            assertThat(r.customerName()).as("nothing typed on the bill").isNull();
        });

        // The sales report carries each item's category (null when it has none).
        var sold = salesReport.report(company.getId(), today, today, null, null, null, null).rows();
        assertThat(sold).anySatisfy(r -> {
            assertThat(r.productName()).isEqualTo("Grilled Chicken Tacos");
            assertThat(r.category()).isEqualTo("Food");
        });
        assertThat(sold).anySatisfy(r -> {
            assertThat(r.productName()).isEqualTo("Brownie Cake");
            assertThat(r.category()).isNull();
        });

        // Close and reconcile, then read the Z-read's new sections.
        setRootCtx();
        BigDecimal takings = sessionService.xRead(sessionUid).cashTenderAmount();
        setCashierCtx();
        sessionService.closeSession(sessionUid, new CloseSessionRequest(takings, null));
        setRootCtx();
        ZReadDto z = sessionService.reconcileSession(sessionUid, new ReconcileSessionRequest(null));
        ZReadBreakdownDto s = z.breakdown();
        BigDecimal total = z.totalSalesAmount();

        assertThat(s.netSalesAmount()).as("same basis as the Z-read total").isEqualByComparingTo(total);
        assertThat(s.discountAmount()).as("the 10% bill discount").isPositive();
        assertThat(s.grossSalesAmount().subtract(s.discountAmount())).isEqualByComparingTo(total);
        assertThat(s.vatAmount()).isPositive();
        assertThat(s.transactionCount()).isEqualTo(z.invoiceCount()).isEqualTo(2);

        assertThat(s.categories()).extracting(ZReadBreakdownDto.Line::name)
                .containsExactlyInAnyOrder("Food", "Drinks", "Uncategorised");
        assertThat(s.departments()).extracting(ZReadBreakdownDto.Line::name)
                .containsExactlyInAnyOrder("Kitchen", "Bar");
        assertThat(s.teamMembers()).singleElement().satisfies(m -> {
            assertThat(m.name()).isEqualTo("Asha Waiter");
            assertThat(m.count()).isEqualTo(2);
        });
        for (var group : List.of(s.categories(), s.departments(), s.teamMembers())) {
            assertThat(group.stream().map(ZReadBreakdownDto.Line::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add))
                    .as("each section sums back to the Z-read total").isEqualByComparingTo(total);
        }

        // Re-reading the Z-read gives the identical sections (timestamps are stored to the
        // microsecond, so the record itself is compared field by field elsewhere).
        assertThat(sessionService.zRead(sessionUid).breakdown()).isEqualTo(s);
    }

    private AddRestaurantOrderLineRequest item(ProductDto p) {
        return new AddRestaurantOrderLineRequest(p.id(), pcsId, null, null);
    }

    private RestaurantOrderDto openWith(ProductDto p) {
        RestaurantOrderDto order = orders.open(new CreateRestaurantOrderRequest(null, table1.uid(), null, null));
        return orders.addLine(order.uid(), item(p));
    }

    private PayRestaurantOrderRequest payment(BigDecimal expected) {
        return new PayRestaurantOrderRequest(sessionUid, customerId, "TZS", null, null, expected, null, null);
    }

    private RestaurantTableState tableState() {
        return setup.listTables(null).stream()
                .filter(t -> t.uid().equals(table1.uid()))
                .findFirst().orElseThrow().state();
    }

    private static RestaurantOrderLineDto lineFor(RestaurantOrderDto order, ProductDto p) {
        return order.lines().stream().filter(l -> l.productId().equals(p.id())).findFirst().orElseThrow();
    }

    private static PrepSlipDto slip(List<PrepSlipDto> slips, String station) {
        return slips.stream().filter(s -> s.stationName().equals(station)).findFirst().orElseThrow();
    }

    /** Real stock behind the sale: the negative-stock guard refuses an oversell. */
    private void stock(ProductDto... products) {
        setRootCtx();
        for (ProductDto product : products) {
            StockReceivedPayload payload = new StockReceivedPayload(
                    product.uid(), company.getId(), branch.getId(), Instant.now(),
                    List.of(new StockReceivedPayload.LineItem(
                            product.id(), product.uid(), null, new BigDecimal("50"), new BigDecimal("500"))));
            txTemplate.execute(s -> {
                outboxPublisher.publish(DomainEventType.STOCK_RECEIVED,
                        DomainEventType.AGG_GOODS_RECEIPT, product.id(), product.uid(),
                        company.getId(), branch.getId(), payload);
                return null;
            });
            Long eventId = domainEventRepo.findAll().stream()
                    .filter(e -> DomainEventType.STOCK_RECEIVED.equals(e.getEventType()))
                    .filter(e -> product.uid().equals(e.getAggregateUid()))
                    .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                    .reduce((a, b) -> b)
                    .map(DomainEvent::getId)
                    .orElseThrow();
            dispatcher.dispatchOne(eventId);
        }
    }

    private void setRootCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "resto_root", true, company.getId(), branch.getId(), null, orgId));
    }

    /** A waiter signed in on their own device or login. */
    private void setWaiterCtx(String username) {
        AppUser w = users.findByUsername(username).orElseThrow();
        RequestContext.set(new RequestContext.Principal(
                w.getId(), username, false, company.getId(), branch.getId(), null, orgId));
    }

    private void setCashierCtx() {
        RequestContext.set(new RequestContext.Principal(
                cashierId, "resto_cashier", false, company.getId(), branch.getId(), null, orgId));
    }
}
