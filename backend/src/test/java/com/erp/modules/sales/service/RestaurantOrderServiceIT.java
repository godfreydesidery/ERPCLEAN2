package com.erp.modules.sales.service;

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
import com.erp.modules.sales.domain.dto.MergeRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PayRestaurantOrderRequest;
import com.erp.modules.sales.domain.dto.PrepSlipDto;
import com.erp.modules.sales.domain.dto.PrepStationDto;
import com.erp.modules.sales.domain.dto.ReplaceCategoryRoutesRequest;
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
    @Autowired private JdbcTemplate            jdbc;

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

    private void setCashierCtx() {
        RequestContext.set(new RequestContext.Principal(
                cashierId, "resto_cashier", false, company.getId(), branch.getId(), null, orgId));
    }
}
