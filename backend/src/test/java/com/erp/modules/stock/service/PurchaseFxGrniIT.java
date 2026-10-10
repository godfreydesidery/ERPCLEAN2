package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.erp.modules.ap.domain.dto.BillLineRequest;
import com.erp.modules.ap.domain.dto.BillMatchResultDto;
import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.service.ApGlSeeder;
import com.erp.modules.ap.service.BillMatchService;
import com.erp.modules.ap.service.SupplierBillService;
import com.erp.modules.fx.domain.entity.CurrencyRate;
import com.erp.modules.fx.repository.CurrencyRateRepository;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.repository.GlConfigRepository;
import com.erp.modules.gl.repository.JournalLineRepository;
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
import com.erp.modules.parties.domain.dto.CreateSupplierRequest;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseReturnRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnDto;
import com.erp.modules.purchases.service.GoodsReceiptService;
import com.erp.modules.purchases.service.PurchaseOrderService;
import com.erp.modules.purchases.service.PurchaseReturnService;
import com.erp.modules.sales.service.TaxRateSeeder;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventStatus;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Wave-3 purchase/FX GL bars, end to end through the real services and the real outbox handlers:
 *
 * <ul>
 *   <li>PUR-07 / ACC-08 — a USD receipt is valued in BASE (stock, moving average, GL 1300/2150).</li>
 *   <li>Bill FX stamp — a matched USD bill keeps its real fx_rate / base gross on the row.</li>
 *   <li>ACC-17 / LBO-13 / PUR-21 — GRNI clears to zero when the bill differs from the receipt (by
 *       price or by rate); the difference goes to Inventory and the moving average follows.</li>
 *   <li>AP-15 / LBO-09 / PUR-14 / LBO-10 — a purchase return's debit note credits GRNI (not
 *       Purchases) and reverses input VAT for a VAT-registered supplier.</li>
 * </ul>
 */
class PurchaseFxGrniIT extends PostgresIntegrationTest {

    @Autowired private OrganisationRepository  organisations;
    @Autowired private CompanyRepository       companies;
    @Autowired private BranchRepository        branches;
    @Autowired private AppUserRepository       users;
    @Autowired private PasswordEncoder         passwordEncoder;
    @Autowired private IamTestData             testData;

    @Autowired private ChartOfAccountService   chartOfAccountService;
    @Autowired private FiscalCalendarService   fiscalCalendarService;
    @Autowired private GlConfigService         glConfigService;
    @Autowired private GlConfigRepository      glConfigRepo;
    @Autowired private JournalLineRepository   journalLines;

    @Autowired private ApGlSeeder              apGlSeeder;
    @Autowired private InventoryGlSeeder       invGlSeeder;
    @Autowired private TaxRateSeeder           taxRateSeeder;
    @Autowired private SupplierBillService     billService;
    @Autowired private BillMatchService        matchService;
    @Autowired private SupplierService         supplierService;

    @Autowired private PurchaseOrderService    poService;
    @Autowired private GoodsReceiptService     grService;
    @Autowired private PurchaseReturnService   returnService;

    @Autowired private ProductService          productService;
    @Autowired private UnitOfMeasureService    unitService;
    @Autowired private StockOnHandRepository   stockOnHandRepo;
    @Autowired private CurrencyRateRepository  currencyRateRepo;

    @Autowired private DomainEventRepository   domainEventRepo;
    @Autowired private DomainEventDispatcher   dispatcher;
    @Autowired private JdbcTemplate            jdbc;

    private Company company;
    private Branch  branch;
    private Long    rootId;
    private String  pcsUid;
    private String  supplierUid;
    private String  vatSupplierUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("PurFx IT Org"));
        company = companies.save(new Company(org, "PFX", "PurFx IT Co"));
        branch  = branches.save(new Branch(company, "PFX1", "PurFx IT Branch"));

        AppUser root = new AppUser("purfx_root", passwordEncoder.encode("PurFx@1!Xxyy"), "PurFx Root");
        root.setRoot(true);
        root.setOrganisationId(org.getId());
        rootId = users.save(root).getId();
        setCtx();

        chartOfAccountService.seedDefaults(company.getId());
        fiscalCalendarService.seedCurrentYear(company.getId());
        glConfigService.seedDefaults(company.getId());
        apGlSeeder.seedDefaults(company.getId());
        invGlSeeder.seedDefaults(company.getId());
        taxRateSeeder.seedDefaults(company.getId());

        pcsUid = unitService.create(
                new CreateUnitOfMeasureRequest(company.getUid(), "PCS", "Pieces")).uid();

        supplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "PurFx Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null)).uid();
        vatSupplierUid = supplierService.create(new CreateSupplierRequest(
                company.getId(), PartyType.INDIVIDUAL, "PurFx VAT Supplier",
                null, null, Boolean.TRUE, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null)).uid();

        // 1 USD = 2,500 TZS today; 2,400 yesterday.
        currencyRateRepo.save(new CurrencyRate(company.getId(), branch.getId(), "USD", "TZS",
                new BigDecimal("2500.00000000"), LocalDate.now(), "SPOT", "MANUAL", rootId));
        currencyRateRepo.save(new CurrencyRate(company.getId(), branch.getId(), "USD", "TZS",
                new BigDecimal("2400.00000000"), LocalDate.now().minusDays(1), "SPOT", "MANUAL",
                rootId));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // PUR-07 / ACC-08
    // =========================================================================

    @Test
    void usdReceipt_isValuedInBaseAtTheReceiptRate() {
        ProductDto p = product("FX-Widget");

        // 10 pcs @ USD 100 = USD 1,000 → TZS 2,500,000 at today's 2,500.
        receive(p, supplierUid, "USD", "10", "100");

        assertThat(balance(GlConfigKey.INVENTORY))
                .as("Inventory 1300 holds the BASE value, not the USD face amount")
                .isEqualByComparingTo("2500000");
        assertThat(balance(GlConfigKey.GRNI)).isEqualByComparingTo("-2500000");
        StockOnHand soh = soh(p.id());
        assertThat(soh.getAvgCost()).isEqualByComparingTo("250000");
        assertThat(soh.getOnHandValue()).isEqualByComparingTo("2500000");
    }

    // =========================================================================
    // Bill FX stamp + ACC-17 (rate difference)
    // =========================================================================

    @Test
    void usdBillAtAnotherRate_stampsItsRate_clearsGrniToZero_andMovesTheDifferenceToStock() {
        ProductDto p = product("FX-Rate-Widget");
        Chain c = receive(p, supplierUid, "USD", "10", "100");   // GRNI CR 2,500,000

        // The supplier's invoice is dated yesterday → USD 1,000 at 2,400 = TZS 2,400,000.
        SupplierBillDto bill = billService.enterBill(new EnterBillRequest(
                company.getUid(), supplierUid, "INV-FX-1", c.poUid(),
                LocalDate.now().minusDays(1), LocalDate.now().plusDays(30),
                BigDecimal.ZERO, "USD", null,
                List.of(new BillLineRequest(null, c.poLineUid(), c.grLineUid(), "Widgets",
                        new BigDecimal("10"), new BigDecimal("100")))));
        BillMatchResultDto result = matchService.runMatch(bill.uid());
        assertThat(result.billStatus()).isEqualTo(SupplierBillStatus.MATCHED);

        // The stamp reached the row (it used to stay at the defaults: fx_rate 1, base NULL).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT fx_rate, base_gross_amount, base_outstanding_amount, rate_at "
                        + "FROM supplier_bills WHERE uid = ?", bill.uid());
        assertThat((BigDecimal) row.get("fx_rate")).isEqualByComparingTo("2400");
        assertThat((BigDecimal) row.get("base_gross_amount")).isEqualByComparingTo("2400000");
        assertThat((BigDecimal) row.get("base_outstanding_amount")).isEqualByComparingTo("2400000");
        assertThat(row.get("rate_at")).isNotNull();

        assertThat(balance(GlConfigKey.ACCOUNTS_PAYABLE))
                .as("AP credited at the bill's rate — and equal to the bill's base outstanding")
                .isEqualByComparingTo("-2400000");
        assertThat(balance(GlConfigKey.GRNI))
                .as("GRNI cleared at the RECEIPT value, so it nets to zero")
                .isEqualByComparingTo("0");
        assertThat(balance(GlConfigKey.INVENTORY))
                .as("the 100,000 rate difference comes off the stock still on hand")
                .isEqualByComparingTo("2400000");
        assertThat(balance(GlConfigKey.COGS)).isEqualByComparingTo("0");

        // The stock sub-ledger follows the GL once the variance event is handled.
        dispatcher.dispatchOne(pendingEvent(DomainEventType.BILL_COST_VARIANCE));
        StockOnHand soh = soh(p.id());
        assertThat(soh.getOnHandValue()).isEqualByComparingTo("2400000");
        assertThat(soh.getAvgCost()).isEqualByComparingTo("240000");
    }

    // =========================================================================
    // ACC-17 / LBO-13 / PUR-21 (price difference, base currency)
    // =========================================================================

    @Test
    void tzsBillAtAHigherPriceWithinTolerance_clearsGrniToZero_andRaisesTheStockCost() {
        ProductDto p = product("Price-Widget");
        Chain c = receive(p, supplierUid, "TZS", "10", "47500");   // GRNI CR 475,000

        SupplierBillDto bill = billService.enterBill(new EnterBillRequest(
                company.getUid(), supplierUid, "INV-PPV-1", c.poUid(),
                LocalDate.now(), LocalDate.now().plusDays(30),
                BigDecimal.ZERO, "TZS", null,
                List.of(new BillLineRequest(null, c.poLineUid(), c.grLineUid(), "Widgets",
                        new BigDecimal("10"), new BigDecimal("48000")))));   // +1.05%, matches
        assertThat(matchService.runMatch(bill.uid()).billStatus())
                .isEqualTo(SupplierBillStatus.MATCHED);

        assertThat(balance(GlConfigKey.GRNI))
                .as("LBO-13: GRNI no longer keeps the 5,000 price difference")
                .isEqualByComparingTo("0");
        assertThat(balance(GlConfigKey.ACCOUNTS_PAYABLE)).isEqualByComparingTo("-480000");
        assertThat(balance(GlConfigKey.INVENTORY)).isEqualByComparingTo("480000");

        dispatcher.dispatchOne(pendingEvent(DomainEventType.BILL_COST_VARIANCE));
        assertThat(soh(p.id()).getAvgCost()).isEqualByComparingTo("48000");
        assertThat(soh(p.id()).getOnHandValue()).isEqualByComparingTo("480000");
    }

    // =========================================================================
    // AP-15 / LBO-09 / PUR-14 / LBO-10 — returns
    // =========================================================================

    @Test
    void returnAfterTheBill_creditsGrniNotPurchases_andReversesInputVat() {
        ProductDto p = product("Return-Widget");
        Chain c = receive(p, vatSupplierUid, "TZS", "10", "1000");   // Inv 10,000 / GRNI 10,000
        SupplierBillDto bill = billService.enterBill(new EnterBillRequest(
                company.getUid(), vatSupplierUid, "INV-RET-1", c.poUid(),
                LocalDate.now(), LocalDate.now().plusDays(30),
                new BigDecimal("1800"), "TZS", null,
                List.of(new BillLineRequest(null, c.poLineUid(), c.grLineUid(), "Widgets",
                        new BigDecimal("10"), new BigDecimal("1000")))));
        assertThat(matchService.runMatch(bill.uid()).billStatus())
                .isEqualTo(SupplierBillStatus.MATCHED);
        assertThat(balance(GlConfigKey.GRNI)).isEqualByComparingTo("0");

        returnTwo(c);

        assertThat(balance(GlConfigKey.GRNI))
                .as("LBO-09: the return's DR GRNI is closed by the debit note's CR GRNI")
                .isEqualByComparingTo("0");
        assertThat(balance(GlConfigKey.PURCHASES))
                .as("AP-15: profit is not credited with the returned goods")
                .isEqualByComparingTo("0");
        assertThat(balance(GlConfigKey.INVENTORY)).isEqualByComparingTo("8000");
        assertThat(balance(GlConfigKey.VAT_INPUT))
                .as("LBO-10: 1,800 claimed on the bill less 360 reversed on the 2,000 returned")
                .isEqualByComparingTo("1440");
        assertThat(balance(GlConfigKey.ACCOUNTS_PAYABLE))
                .as("11,800 billed less the 2,360 debit note")
                .isEqualByComparingTo("-9440");
    }

    @Test
    void returnBeforeTheBill_thenTheFullBill_leavesGrniAtZero() {
        ProductDto p = product("Return-Early-Widget");
        Chain c = receive(p, supplierUid, "TZS", "10", "1000");
        returnTwo(c);

        SupplierBillDto bill = billService.enterBill(new EnterBillRequest(
                company.getUid(), supplierUid, "INV-RET-2", c.poUid(),
                LocalDate.now(), LocalDate.now().plusDays(30),
                BigDecimal.ZERO, "TZS", null,
                List.of(new BillLineRequest(null, c.poLineUid(), c.grLineUid(), "Widgets",
                        new BigDecimal("10"), new BigDecimal("1000")))));
        assertThat(matchService.runMatch(bill.uid()).billStatus())
                .isEqualTo(SupplierBillStatus.MATCHED);

        assertThat(balance(GlConfigKey.GRNI)).isEqualByComparingTo("0");
        assertThat(balance(GlConfigKey.PURCHASES)).isEqualByComparingTo("0");
        assertThat(balance(GlConfigKey.INVENTORY)).isEqualByComparingTo("8000");
        assertThat(balance(GlConfigKey.ACCOUNTS_PAYABLE))
                .as("the supplier is owed 10,000 billed less the 2,000 credit")
                .isEqualByComparingTo("-8000");
    }

    // =========================================================================

    private record Chain(String poUid, String poLineUid, String grUid, String grLineUid) {}

    private void setCtx() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "purfx_root", true, company.getId(), branch.getId(), null));
    }

    private ProductDto product(String name) {
        setCtx();
        return productService.create(new CreateProductRequest(
                company.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
    }

    private Chain receive(ProductDto p, String supplier, String currency, String qty, String cost) {
        setCtx();
        PurchaseOrderDto draft = poService.create(new CreatePurchaseOrderRequest(
                company.getUid(), supplier, currency, null, null,
                List.of(new AddPurchaseOrderLineRequest(p.uid(), pcsUid, new BigDecimal(qty),
                        new BigDecimal(cost), null))));
        PurchaseOrderDto placed = poService.placeOrder(draft.uid());
        String poLineUid = placed.lines().get(0).uid();
        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), "PurFx IT receipt",
                List.of(new GoodsReceiptLineRequest(poLineUid, new BigDecimal(qty)))));
        dispatcher.dispatchOne(pendingEvent(DomainEventType.STOCK_RECEIVED));
        setCtx();
        return new Chain(placed.uid(), poLineUid, gr.uid(), gr.lines().get(0).uid());
    }

    private void returnTwo(Chain c) {
        setCtx();
        PurchaseReturnDto ret = returnService.create(new CreatePurchaseReturnRequest(
                company.getUid(), c.grUid(), "Damaged",
                List.of(new CreatePurchaseReturnRequest.ReturnLineRequest(
                        c.grLineUid(), new BigDecimal("2")))));
        returnService.confirm(ret.uid());
        dispatcher.dispatchOne(pendingEvent(DomainEventType.PURCHASE_RETURNED));
        setCtx();
    }

    private Long pendingEvent(String eventType) {
        return domainEventRepo.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .reduce((a, b) -> b)
                .map(DomainEvent::getId)
                .orElseThrow(() -> new AssertionError("No PENDING event of type: " + eventType));
    }

    private StockOnHand soh(Long productId) {
        return stockOnHandRepo
                .findByCompanyIdAndBranchIdAndProductId(company.getId(), branch.getId(), productId)
                .orElseThrow(() -> new AssertionError("No on-hand row for productId=" + productId));
    }

    /** Net balance (DR − CR) of the account mapped to {@code key}. */
    private BigDecimal balance(GlConfigKey key) {
        Long accountId = glConfigRepo.findByCompanyIdAndConfigKey(company.getId(), key)
                .orElseThrow().getAccountId();
        BigDecimal b = journalLines.accountBalance(company.getId(), accountId);
        return b != null ? b : BigDecimal.ZERO;
    }
}
