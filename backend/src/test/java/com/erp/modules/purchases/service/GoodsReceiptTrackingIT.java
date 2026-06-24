package com.erp.modules.purchases.service;

import static org.assertj.core.api.Assertions.assertThat;

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
import com.erp.modules.parties.domain.dto.SupplierDto;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.domain.enums.SupplierKind;
import com.erp.modules.parties.service.SupplierService;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.purchases.domain.dto.AddPurchaseOrderLineRequest;
import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.CreatePurchaseOrderRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineRequest;
import com.erp.modules.purchases.domain.dto.PurchaseOrderDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.modules.purchases.repository.GoodsReceiptLineSerialRepository;
import com.erp.modules.stock.repository.StockBatchRepository;
import com.erp.modules.stock.repository.StockSerialRepository;
import com.erp.modules.stock.service.StockLocationSeeder;
import com.erp.platform.events.DomainEventDispatcher;
import com.erp.platform.events.DomainEventRepository;
import com.erp.platform.events.DomainEventStatus;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for V76 lot/serial tracking at Goods Receipt (GoodsReceiptTrackingIT).
 *
 * <p>Scenarios:
 * <ol>
 *   <li>Lot-tracked + serial-tracked product: receive with lotNumber, expiryDate, N serials →
 *       (a) GRN line persists lot/expiry, (b) serial child rows in goods_receipt_line_serials,
 *       (c) after dispatch: stock_batches row at default location with expiry set,
 *       (d) N stock_serials rows at same location,
 *       (e) StockBatchRepository.findExpiring returns the batch.</li>
 *   <li>No lot/serials provided → GRN succeeds; zero batch/serial rows written.</li>
 *   <li>Response DTO carries lot/expiry/serialNumbers correctly.</li>
 * </ol>
 */
class GoodsReceiptTrackingIT extends PostgresIntegrationTest {

    @Autowired private ChartOfAccountService    chartOfAccountService;
    @Autowired private FiscalCalendarService    fiscalCalendarService;
    @Autowired private GlConfigService          glConfigService;

    @Autowired private PurchaseOrderService     poService;
    @Autowired private GoodsReceiptService      grService;
    @Autowired private SupplierService          supplierService;
    @Autowired private ProductService           productService;
    @Autowired private ProductRepository        productRepo;
    @Autowired private UnitOfMeasureService     unitService;
    @Autowired private DomainEventRepository    domainEventRepo;
    @Autowired private DomainEventDispatcher    dispatcher;
    @Autowired private StockBatchRepository     batchRepo;
    @Autowired private StockSerialRepository    serialRepo;
    @Autowired private GoodsReceiptLineSerialRepository grLineSerialRepo;
    @Autowired private StockLocationSeeder      locationSeeder;

    @Autowired private OrganisationRepository   organisations;
    @Autowired private CompanyRepository        companies;
    @Autowired private BranchRepository         branches;
    @Autowired private AppUserRepository        users;
    @Autowired private PasswordEncoder          passwordEncoder;
    @Autowired private IamTestData              testData;

    private Company companyA;
    private Branch  branchA;
    private Long    rootId;
    private String  pcsUid;
    private String  supplierUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();

        Organisation org = organisations.save(new Organisation("Track IT Org"));
        companyA = companies.save(new Company(org, "TRACKA", "Track IT Co A"));
        branchA  = branches.save(new Branch(companyA, "TRACK-A1", "Track IT Branch A1"));

        AppUser root = new AppUser("track_root", passwordEncoder.encode("RootPass1!"), "Track Root");
        root.setRoot(true);
        root   = users.save(root);
        rootId = root.getId();

        setContext(companyA, branchA);

        // GL foundations required by GoodsReceiptStockHandler
        chartOfAccountService.seedDefaults(companyA.getId());
        fiscalCalendarService.seedCurrentYear(companyA.getId());
        glConfigService.seedDefaults(companyA.getId());

        // Seed default + in-transit stock locations for the branch (V76 needs default location)
        locationSeeder.seedDefaults(companyA.getId(), branchA.getId(), branchA.getCode());

        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));
        pcsUid = pcs.uid();

        SupplierDto supplier = supplierService.create(new CreateSupplierRequest(
                companyA.getId(), PartyType.INDIVIDUAL, "Track Supplier",
                null, null, null, null, null, null, null, null, null, null, null, null,
                SupplierKind.GOODS, null, null));
        supplierUid = supplier.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // =========================================================================
    // 1a. Lot-tracked product: receive with lotNumber + expiryDate → batch row created
    // =========================================================================

    @Test
    void receive_lotTracked_createsBatchRow() {
        // Arrange: lot-tracked product (NOT serial-tracked — constraint is mutually exclusive)
        ProductDto product = stockableProduct("Pharma-Lot");
        enableTracking(product.uid(), true /* lot */, false /* serial */);

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("3"), new BigDecimal("500"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        LocalDate expiry = LocalDate.now().plusYears(2);

        // Act: receive with lot number and expiry date
        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), "Lot-tracked test",
                List.of(new GoodsReceiptLineRequest(
                        poLine.uid(), new BigDecimal("3"),
                        "LOT-2024-001", null, expiry, List.of()))));

        // Assert: GRN line DTO carries the lot/expiry fields
        assertThat(gr.lines()).hasSize(1);
        var lineDto = gr.lines().get(0);
        assertThat(lineDto.lotNumber()).isEqualTo("LOT-2024-001");
        assertThat(lineDto.expiryDate()).isEqualTo(expiry);
        assertThat(lineDto.serialNumbers()).isEmpty();

        // Dispatch STOCK.RECEIVED
        Long eventId = pendingEventId(DomainEventType.STOCK_RECEIVED);
        dispatcher.dispatchOne(eventId);

        // Assert (a): stock_batches row at default location with expiry set
        var batches = batchRepo.findAll().stream()
                .filter(b -> b.getProductId().equals(product.id())
                        && b.getCompanyId().equals(companyA.getId()))
                .toList();
        assertThat(batches).hasSize(1);
        assertThat(batches.get(0).getLotNumber()).isEqualTo("LOT-2024-001");
        assertThat(batches.get(0).getExpiryDate()).isEqualTo(expiry);
        assertThat(batches.get(0).getQtyOnHand()).isEqualByComparingTo(new BigDecimal("3"));

        // Assert (b): StockBatchRepository.findExpiring returns this batch
        var expiring = batchRepo.findExpiring(
                companyA.getId(), LocalDate.now().plusYears(3),
                org.springframework.data.domain.Pageable.unpaged());
        assertThat(expiring.getContent()).anyMatch(b ->
                b.getLotNumber().equals("LOT-2024-001")
                        && b.getProductId().equals(product.id()));
    }

    // =========================================================================
    // 1b. Serial-tracked product: receive with N serials → serial rows created
    // =========================================================================

    @Test
    void receive_serialTracked_createsSerialRows() {
        // Arrange: serial-tracked product (NOT lot-tracked — mutually exclusive)
        ProductDto product = stockableProduct("PhoneX");
        enableTracking(product.uid(), false /* lot */, true /* serial */);

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("3"), new BigDecimal("500"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        List<String> serials = List.of("SN-001", "SN-002", "SN-003");

        // Act: receive with 3 serial numbers
        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), "Serial-tracked test",
                List.of(new GoodsReceiptLineRequest(
                        poLine.uid(), new BigDecimal("3"),
                        null, null, null, serials))));

        // Assert: GRN line DTO carries the serial numbers
        assertThat(gr.lines()).hasSize(1);
        var lineDto = gr.lines().get(0);
        assertThat(lineDto.serialNumbers()).containsExactlyInAnyOrder("SN-001", "SN-002", "SN-003");

        // Assert: serial child rows persisted in goods_receipt_line_serials
        var grLineSerials = grLineSerialRepo.findByGoodsReceiptLineId(lineDto.id());
        assertThat(grLineSerials).hasSize(3);
        assertThat(grLineSerials).extracting(s -> s.getSerialNumber())
                .containsExactlyInAnyOrder("SN-001", "SN-002", "SN-003");

        // Dispatch STOCK.RECEIVED
        Long eventId = pendingEventId(DomainEventType.STOCK_RECEIVED);
        dispatcher.dispatchOne(eventId);

        // Assert: N stock_serials rows at default location
        var stockSerials = serialRepo.findAll().stream()
                .filter(s -> s.getProductId().equals(product.id())
                        && s.getCompanyId().equals(companyA.getId()))
                .toList();
        assertThat(stockSerials).hasSize(3);
        assertThat(stockSerials).extracting(s -> s.getSerialNumber())
                .containsExactlyInAnyOrder("SN-001", "SN-002", "SN-003");
    }

    // =========================================================================
    // 2. No lot/serials provided → receipt succeeds; no batch/serial rows written
    // =========================================================================

    @Test
    void receive_noLotOrSerials_succeedsWithNoBatchOrSerialRows() {
        ProductDto product = stockableProduct("GenericWidget");

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("5"), new BigDecimal("100"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        // Act: plain receipt — no lot, no expiry, no serials (back-compat constructor)
        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), "Plain receipt",
                List.of(new GoodsReceiptLineRequest(poLine.uid(), new BigDecimal("5")))));

        assertThat(gr.lines()).hasSize(1);
        var lineDto = gr.lines().get(0);
        assertThat(lineDto.lotNumber()).isNull();
        assertThat(lineDto.expiryDate()).isNull();
        assertThat(lineDto.serialNumbers()).isEmpty();

        // No serial child rows written
        assertThat(grLineSerialRepo.findByGoodsReceiptLineId(lineDto.id())).isEmpty();

        // Dispatch: no batch/serial rows created for an untracked product with no data
        Long eventId = pendingEventId(DomainEventType.STOCK_RECEIVED);
        dispatcher.dispatchOne(eventId);

        long batchesForProduct = batchRepo.findAll().stream()
                .filter(b -> b.getProductId().equals(product.id())
                        && b.getCompanyId().equals(companyA.getId()))
                .count();
        assertThat(batchesForProduct).isZero();

        long serialsForProduct = serialRepo.findAll().stream()
                .filter(s -> s.getProductId().equals(product.id())
                        && s.getCompanyId().equals(companyA.getId()))
                .count();
        assertThat(serialsForProduct).isZero();
    }

    // =========================================================================
    // 3. Duplicate serials in request are deduplicated
    // =========================================================================

    @Test
    void receive_duplicateSerials_areDeduped() {
        ProductDto product = stockableProduct("DedupePhone");
        enableTracking(product.uid(), false /* lot */, true /* serial */);

        PurchaseOrderDto placed = placeOrderWithLine(product.uid(),
                new BigDecimal("2"), new BigDecimal("200"));
        PurchaseOrderLineDto poLine = placed.lines().get(0);

        // Supply duplicate serial
        GoodsReceiptDto gr = grService.createAndReceive(new CreateGoodsReceiptRequest(
                placed.uid(), "Dedupe test",
                List.of(new GoodsReceiptLineRequest(
                        poLine.uid(), new BigDecimal("2"),
                        null, null, null,
                        List.of("SN-DUP", "SN-DUP", " SN-DUP ")))));

        var lineDto = gr.lines().get(0);
        // Only one unique serial row stored
        var stored = grLineSerialRepo.findByGoodsReceiptLineId(lineDto.id());
        assertThat(stored).hasSize(1);
        assertThat(stored.get(0).getSerialNumber()).isEqualTo("SN-DUP");
    }

    // =========================================================================
    // Private helpers
    // =========================================================================

    private void setContext(Company company, Branch branch) {
        RequestContext.set(new RequestContext.Principal(
                rootId, "track_root", true, company.getId(), branch.getId(), null));
    }

    private ProductDto stockableProduct(String name) {
        return productService.create(new CreateProductRequest(
                companyA.getUid(), null, name, null,
                ProductType.GOODS, true, true, pcsUid, null, VatStatus.STANDARD,
                null, null, null, null, null, null, null, null, null));
    }

    /** Enable lot/serial tracking on a product by setting the Lombok @Setter fields directly. */
    private void enableTracking(String productUid, boolean lotTracked, boolean serialTracked) {
        Product p = productRepo.findByCompanyIdAndUid(companyA.getId(), productUid)
                .orElseThrow(() -> new AssertionError("Product not found: " + productUid));
        p.setLotTracked(lotTracked);
        p.setSerialTracked(serialTracked);
        productRepo.save(p);
    }

    private PurchaseOrderDto placeOrderWithLine(String productUid, BigDecimal qty,
                                                 BigDecimal unitCost) {
        PurchaseOrderDto draft = poService.create(new CreatePurchaseOrderRequest(
                companyA.getUid(), supplierUid, "TZS", null, null,
                List.of(new AddPurchaseOrderLineRequest(productUid, pcsUid, qty, unitCost, null))));
        return poService.placeOrder(draft.uid());
    }

    private Long pendingEventId(String eventType) {
        return domainEventRepo.findAll().stream()
                .filter(e -> eventType.equals(e.getEventType()))
                .filter(e -> DomainEventStatus.PENDING == e.getStatus())
                .reduce((a, b) -> b)
                .map(DomainEvent::getId)
                .orElseThrow(() -> new AssertionError("No PENDING event of type: " + eventType));
    }
}
