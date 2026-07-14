package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.erp.modules.iam.domain.entity.AppUser;
import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.iam.repository.OrganisationRepository;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.enums.ProductType;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.modules.products.service.ProductService;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.modules.stock.domain.dto.OpeningBalanceRequest;
import com.erp.modules.stock.domain.dto.StockMovementDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.AdjustmentReason;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.bulk.ImportContext;
import com.erp.platform.bulk.ImportMode;
import com.erp.platform.bulk.ImportRow;
import com.erp.platform.bulk.RowAction;
import com.erp.platform.bulk.RowOutcome;
import com.erp.platform.security.RequestContext;
import com.erp.support.IamTestData;
import com.erp.support.PostgresIntegrationTest;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;

/**
 * Integration tests for the bulk STOCK on-hand import ({@link StockImportHandler}) against real
 * Postgres. Covers the absolute "set on-hand to a counted level" semantics: a commit posts the
 * difference as an adjustment (reason defaults to COUNT_CORRECTION); a never-tracked product is
 * seeded from zero; an unchanged level and a blank cell are no-ops; a negative counted quantity is
 * rejected; a custom reason flows through; and the export pre-fills the current level for the
 * round-trip.
 */
class StockImportHandlerIT extends PostgresIntegrationTest {

    @Autowired private StockImportHandler handler;
    @Autowired private StockService stockService;
    @Autowired private ProductService productService;
    @Autowired private UnitOfMeasureService unitService;
    @Autowired private StockOnHandRepository onHands;
    @Autowired private StockLocationRepository locations;
    @Autowired private StockMovementRepository movements;
    @Autowired private org.springframework.transaction.support.TransactionTemplate txTemplate;
    @Autowired private OrganisationRepository organisations;
    @Autowired private CompanyRepository companies;
    @Autowired private BranchRepository branches;
    @Autowired private AppUserRepository users;
    @Autowired private PasswordEncoder passwordEncoder;
    @Autowired private IamTestData testData;

    private Company companyA;
    private Branch branchA;
    private Long rootId;
    private String pcsUid;

    @BeforeEach
    void setUp() {
        testData.clearAll();
        Organisation org = organisations.save(new Organisation("Stock Import Org"));
        companyA = companies.save(new Company(org, "SICA", "Stock Import Co"));
        branchA = branches.save(new Branch(companyA, "SI-A1", "SI Branch"));

        AppUser root = new AppUser("si_root", passwordEncoder.encode("RootPass1!"), "SI Root");
        root.setRoot(true);
        root = users.save(root);
        rootId = root.getId();
        asRoot();

        UnitOfMeasureDto pcs = unitService.create(
                new CreateUnitOfMeasureRequest(companyA.getUid(), "PCS", "Pieces"));
        pcsUid = pcs.uid();
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -----------------------------------------------------------------------

    @Test
    void commit_setsOnHandToCountedLevel_postingTheDifference() {
        ProductDto product = stockableProduct("Widget");
        stockService.openingBalance(new OpeningBalanceRequest(product.uid(), new BigDecimal("50"), null));

        RowOutcome o = handler.process(companyA.getId(),
                row("Product Code", product.code(), "New On-Hand Qty", "48"),
                ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.UPDATE);
        assertThat(o.reference()).contains(product.code()).contains("-2");
        assertThat(onHand(product.id())).isEqualByComparingTo("48");

        StockMovement last = lastMovement(product.id());
        assertThat(last.getMovementType()).isEqualTo(MovementType.ADJUSTMENT);
        assertThat(StockMovementDto.from(last).quantity()).isEqualByComparingTo("-2");
        assertThat(last.getReasonCode()).isEqualTo(AdjustmentReason.COUNT_CORRECTION.name());
    }

    @Test
    void commit_neverTrackedProduct_seedsInitialLevelFromZero() {
        ProductDto product = stockableProduct("Fresh");

        RowOutcome o = handler.process(companyA.getId(),
                row("Product Code", product.code(), "New On-Hand Qty", "30"),
                ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.UPDATE);
        assertThat(onHand(product.id())).isEqualByComparingTo("30");
    }

    @Test
    void commit_unchangedLevel_isNoOp() {
        ProductDto product = stockableProduct("Same");
        stockService.openingBalance(new OpeningBalanceRequest(product.uid(), new BigDecimal("50"), null));
        long movesBefore = movements.count();

        RowOutcome o = handler.process(companyA.getId(),
                row("Product Code", product.code(), "New On-Hand Qty", "50"),
                ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.SKIP);
        assertThat(onHand(product.id())).isEqualByComparingTo("50");
        assertThat(movements.count()).isEqualTo(movesBefore); // no adjustment posted
    }

    @Test
    void commit_blankQuantity_isSkipped() {
        ProductDto product = stockableProduct("Blank");
        stockService.openingBalance(new OpeningBalanceRequest(product.uid(), new BigDecimal("50"), null));

        RowOutcome o = handler.process(companyA.getId(),
                row("Product Code", product.code()),
                ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.SKIP);
        assertThat(onHand(product.id())).isEqualByComparingTo("50"); // untouched
    }

    @Test
    void commit_negativeQuantity_isRejected() {
        ProductDto product = stockableProduct("Neg");

        assertThatThrownBy(() -> handler.process(companyA.getId(),
                row("Product Code", product.code(), "New On-Hand Qty", "-5"),
                ImportMode.COMMIT, new ImportContext()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    void commit_unknownProductCode_isRejected() {
        assertThatThrownBy(() -> handler.process(companyA.getId(),
                row("Product Code", "NOPE-9999", "New On-Hand Qty", "10"),
                ImportMode.COMMIT, new ImportContext()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("was not found");
    }

    @Test
    void commit_customReason_flowsThroughToTheMovement() {
        ProductDto product = stockableProduct("Damaged");
        stockService.openingBalance(new OpeningBalanceRequest(product.uid(), new BigDecimal("50"), null));

        RowOutcome o = handler.process(companyA.getId(),
                row("Product Code", product.code(), "New On-Hand Qty", "45", "Reason", "DAMAGE",
                        "Note", "flood"),
                ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.UPDATE);
        assertThat(onHand(product.id())).isEqualByComparingTo("45");
        StockMovement last = lastMovement(product.id());
        assertThat(last.getReasonCode()).isEqualTo(AdjustmentReason.DAMAGE.name());
        assertThat(last.getNote()).isEqualTo("flood");
    }

    @Test
    void commit_productStockedAtMultipleLocations_isSkippedNotErrored() {
        ProductDto product = stockableProduct("MultiLoc");
        // Opening balance posts at the branch default location (seeded lazily).
        stockService.openingBalance(new OpeningBalanceRequest(product.uid(), new BigDecimal("30"), null));
        // Seed a second location + a directly-written on-hand row there (mirrors StockServiceImplIT).
        StockLocation loc2 = locations.save(new StockLocation(
                companyA.getId(), branchA.getId(), "LOC2-" + branchA.getId(), "Warehouse B",
                LocationType.WAREHOUSE, false, null));
        txTemplate.execute(s -> {
            StockOnHand soh = onHands.save(new StockOnHand(
                    companyA.getId(), branchA.getId(), loc2.getId(), product.id()));
            soh.applyDelta(new BigDecimal("20"), null);
            return onHands.save(soh); // product now at 2 locations: default=30, loc2=20 (total 50)
        });
        long movesBefore = movements.count();

        RowOutcome o = handler.process(companyA.getId(),
                row("Product Code", product.code(), "New On-Hand Qty", "48"),
                ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.SKIP);
        assertThat(o.message()).contains("locations");
        assertThat(onHand(product.id())).isEqualByComparingTo("50");     // untouched
        assertThat(movements.count()).isEqualTo(movesBefore);            // nothing posted
    }

    /**
     * A count corrects the stock WHERE IT SITS. When a product's only on-hand row is at a
     * non-default location, the adjustment must land on THAT location — not on the branch default,
     * which would split the product across two on-hand rows (and then blow up on the single-row
     * re-read inside adjust()).
     */
    @Test
    void commit_productHeldOnlyAtANonDefaultLocation_correctsThatLocation() {
        ProductDto product = stockableProduct("OffSite");
        StockLocation loc2 = locations.save(new StockLocation(
                companyA.getId(), branchA.getId(), "LOC2-" + branchA.getId(), "Warehouse B",
                LocationType.WAREHOUSE, false, null));
        txTemplate.execute(s -> {
            StockOnHand soh = onHands.save(new StockOnHand(
                    companyA.getId(), branchA.getId(), loc2.getId(), product.id()));
            soh.applyDelta(new BigDecimal("30"), null);
            return onHands.save(soh);
        });

        RowOutcome o = handler.process(companyA.getId(),
                row("Product Code", product.code(), "New On-Hand Qty", "42"),
                ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.UPDATE);
        assertThat(onHands.findAllByCompanyIdAndBranchIdAndProductId(
                        companyA.getId(), branchA.getId(), product.id()))
                .singleElement()   // corrected in place — NOT a second row at the branch default
                .satisfies(soh -> {
                    assertThat(soh.getLocationId()).isEqualTo(loc2.getId());
                    assertThat(soh.getQuantity()).isEqualByComparingTo("42");
                });
        assertThat(lastMovement(product.id()).getLocationId()).isEqualTo(loc2.getId());
    }

    @Test
    void export_prefillsCurrentOnHand_andLeavesNewBlank() {
        ProductDto product = stockableProduct("Exported");
        stockService.openingBalance(new OpeningBalanceRequest(product.uid(), new BigDecimal("50"), null));

        LinkedHashMap<String, String> widget = handler.exportRows(companyA.getId(), Map.of()).stream()
                .filter(r -> product.code().equals(r.get("Product Code")))
                .findFirst().orElseThrow();

        assertThat(widget.get("Product Name")).isEqualTo("Exported");
        assertThat(new BigDecimal(widget.get("Current On-Hand"))).isEqualByComparingTo("50");
        assertThat(widget.get("New On-Hand Qty")).isEmpty();
    }

    @Test
    void export_thenReuploadWithEditedLevel_updates() {
        ProductDto product = stockableProduct("RoundTrip");
        stockService.openingBalance(new OpeningBalanceRequest(product.uid(), new BigDecimal("50"), null));

        LinkedHashMap<String, String> exported = handler.exportRows(companyA.getId(), Map.of()).stream()
                .filter(r -> product.code().equals(r.get("Product Code")))
                .findFirst().orElseThrow();
        exported.put("New On-Hand Qty", "60"); // count only this one, upload the sheet back

        RowOutcome o = handler.process(companyA.getId(),
                new ImportRow(2, exported), ImportMode.COMMIT, new ImportContext());

        assertThat(o.action()).isEqualTo(RowAction.UPDATE);
        assertThat(onHand(product.id())).isEqualByComparingTo("60");
    }

    // -----------------------------------------------------------------------

    private void asRoot() {
        RequestContext.set(new RequestContext.Principal(
                rootId, "si_root", true, companyA.getId(), branchA.getId(), null));
    }

    private ProductDto stockableProduct(String name) {
        return productService.create(new CreateProductRequest(
                companyA.getUid(), null, name, null, ProductType.GOODS, true, true, pcsUid,
                null, VatStatus.STANDARD, null, null, null, null, null, null, null, null, null));
    }

    private BigDecimal onHand(Long productId) {
        return onHands.findAllByCompanyIdAndBranchIdAndProductId(companyA.getId(), branchA.getId(), productId)
                .stream()
                .map(soh -> soh.getQuantity() != null ? soh.getQuantity() : BigDecimal.ZERO)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** The most recently occurring movement for a product (adjustment sits after the opening balance). */
    private StockMovement lastMovement(Long productId) {
        List<StockMovement> all = movements
                .findByCompanyIdAndBranchIdAndProductIdOrderByOccurredAtAsc(
                        companyA.getId(), branchA.getId(), productId, Pageable.unpaged())
                .getContent();
        assertThat(all).isNotEmpty();
        return all.get(all.size() - 1);
    }

    /** Build an ImportRow from header/value pairs (row number is irrelevant to the assertions). */
    private static ImportRow row(String... kv) {
        Map<String, String> cells = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            cells.put(kv[i], kv[i + 1]);
        }
        return new ImportRow(2, cells);
    }
}
