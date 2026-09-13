package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.products.domain.dto.ProductBulkPackDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.stock.domain.dto.CreateStockTransferRequest;
import com.erp.modules.stock.domain.dto.StockTransferDto;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.entity.StockTransferLine;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockTransfer;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.modules.stock.repository.StockTransferLineRepository;
import com.erp.modules.stock.repository.StockTransferRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Unit tests for {@link StockTransferServiceImpl} branch/location name enrichment.
 *
 * <p>A branch manager could not see WHICH branch/location a stock transfer moved stock between —
 * only the internal numeric ids travelled in the DTO. Fixed by resolving
 * sourceBranchName/sourceBranchCode/destBranchName/destBranchCode/sourceLocationName/
 * destLocationName at read time via {@link BranchRepository}/{@link StockLocationRepository},
 * mirroring {@code SalesOrderServiceImpl.buildDto}.
 *
 * <p>Only the enrichment path (getByUid) is exercised here; the rest of
 * {@link StockTransferServiceImpl} is covered by its dedicated *IT suites.
 */
@ExtendWith(MockitoExtension.class)
class StockTransferServiceImplTest {

    @Mock StockTransferRepository transfers;
    @Mock StockTransferLineRepository transferLines;
    @Mock StockOnHandRepository onHands;
    @Mock StockPostingService posting;
    @Mock InventoryValuationService valuation;
    @Mock com.erp.modules.products.service.ProductService productService;
    @Mock LocationResolver locationResolver;
    @Mock WarehouseNumberGenerator numberGenerator;
    @Mock com.erp.platform.events.OutboxPublisher outbox;
    @Mock ScopeGuard scopeGuard;
    @Mock com.erp.platform.audit.AuditService audit;
    @Mock BranchRepository branches;
    @Mock StockLocationRepository locations;

    @InjectMocks StockTransferServiceImpl service;

    private static final Long COMPANY_ID    = 1L;
    private static final Long SRC_BRANCH_ID = 10L;
    private static final Long DST_BRANCH_ID = 20L;
    private static final Long SRC_LOC_ID    = 100L;
    private static final Long DST_LOC_ID    = 200L;

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Principal(
                1L, "tester", false, COMPANY_ID, SRC_BRANCH_ID, "127.0.0.1"));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    @Test
    void getByUid_resolvesSourceAndDestBranchAndLocationNames() {
        StockTransfer transfer = transferWithId(700L, "STUID00000000000000000001");
        when(transfers.findByUid("STUID00000000000000000001")).thenReturn(Optional.of(transfer));
        when(transferLines.findByStockTransferIdOrderByLineNoAsc(700L)).thenReturn(List.of());
        when(branches.findById(SRC_BRANCH_ID)).thenReturn(Optional.of(branch("SRC-01", "Source Branch")));
        when(branches.findById(DST_BRANCH_ID)).thenReturn(Optional.of(branch("DST-01", "Dest Branch")));
        when(locations.findById(SRC_LOC_ID)).thenReturn(Optional.of(location("Source Store")));
        when(locations.findById(DST_LOC_ID)).thenReturn(Optional.of(location("Dest Store")));

        StockTransferDto dto = service.getByUid("STUID00000000000000000001");

        assertThat(dto.sourceBranchId()).isEqualTo(SRC_BRANCH_ID);
        assertThat(dto.sourceBranchName()).isEqualTo("Source Branch");
        assertThat(dto.sourceBranchCode()).isEqualTo("SRC-01");
        assertThat(dto.sourceLocationName()).isEqualTo("Source Store");
        assertThat(dto.destBranchId()).isEqualTo(DST_BRANCH_ID);
        assertThat(dto.destBranchName()).isEqualTo("Dest Branch");
        assertThat(dto.destBranchCode()).isEqualTo("DST-01");
        assertThat(dto.destLocationName()).isEqualTo("Dest Store");
    }

    @Test
    void getByUid_namesNull_whenBranchAndLocationRowsMissing() {
        StockTransfer transfer = transferWithId(701L, "STUID00000000000000000002");
        when(transfers.findByUid("STUID00000000000000000002")).thenReturn(Optional.of(transfer));
        when(transferLines.findByStockTransferIdOrderByLineNoAsc(701L)).thenReturn(List.of());
        when(branches.findById(SRC_BRANCH_ID)).thenReturn(Optional.empty());
        when(branches.findById(DST_BRANCH_ID)).thenReturn(Optional.empty());
        when(locations.findById(SRC_LOC_ID)).thenReturn(Optional.empty());
        when(locations.findById(DST_LOC_ID)).thenReturn(Optional.empty());

        StockTransferDto dto = service.getByUid("STUID00000000000000000002");

        assertThat(dto.sourceBranchName()).isNull();
        assertThat(dto.sourceBranchCode()).isNull();
        assertThat(dto.sourceLocationName()).isNull();
        assertThat(dto.destBranchName()).isNull();
        assertThat(dto.destBranchCode()).isNull();
        assertThat(dto.destLocationName()).isNull();
        // never throws — missing rows degrade to null names, they never fail the read.
    }

    // -------------------------------------------------------------------------
    // create(): the unit and the value the line carries (Kilimanjaro 2026-09-12 #5)
    // -------------------------------------------------------------------------

    /**
     * Both fields were passed as null at create and nothing has ever written them since — the
     * columns are {@code updatable = false}. The storekeeper saw the result: a permanently blank
     * Unit column, and a Value column that rendered 0.00 because the screen coerced null to zero.
     */
    @Test
    void create_snapshotsTheBaseUnitAndThelineValue() {
        List<StockTransferLine> saved = stubCreate(new BigDecimal("1500.00"));

        service.create(new CreateStockTransferRequest(
                "SRCLOCUID0000000000000001", "DSTLOCUID0000000000000001",
                LocalDate.now(), "INSTANT", null,
                List.of(new CreateStockTransferRequest.LineRequest(
                        "PRODUID00000000000000001", new BigDecimal("4"), null))));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUnitName()).isEqualTo("Bottle");
        assertThat(saved.get(0).getValueAmount()).isEqualByComparingTo("6000.00");
    }

    /**
     * An uncosted product has an UNKNOWN value, not a zero one. Storing zero would put a confident
     * 0.00 on a transfer document for goods that are worth something — the same rule the sales
     * margin fix settled: what the system cannot know, it says it cannot know.
     */
    @Test
    void create_leavesTheValueNullWhenTheProductHasNeverBeenCosted() {
        List<StockTransferLine> saved = stubCreate(null);

        service.create(new CreateStockTransferRequest(
                "SRCLOCUID0000000000000001", "DSTLOCUID0000000000000001",
                LocalDate.now(), "INSTANT", null,
                List.of(new CreateStockTransferRequest.LineRequest(
                        "PRODUID00000000000000001", new BigDecimal("4"), null))));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUnitName()).isEqualTo("Bottle");
        assertThat(saved.get(0).getValueAmount()).isNull();
    }

    /**
     * A pack unit multiplies into base units. avg_cost is held PER BASE UNIT, so the line value must
     * be priced off the converted quantity — pricing "2 cartons" at the cost of 2 pieces understates
     * the line by the pack factor, and the transfer document then reports a fraction of what moved.
     */
    @Test
    void create_convertsAPackUnitToBaseUnitsAndPricesOffTheConvertedQuantity() {
        List<StockTransferLine> saved = stubCreate(new BigDecimal("1500.00"));
        when(productService.listBulkPacks("PRODUID00000000000000001")).thenReturn(List.of(
                new ProductBulkPackDto(9L, "PACKUID00000000000000001", 5L,
                        "UNITUID00000000000000CTN", "CTN", "Carton",
                        new BigDecimal("12"), null, false, false, List.of())));

        service.create(new CreateStockTransferRequest(
                "SRCLOCUID0000000000000001", "DSTLOCUID0000000000000001",
                LocalDate.now(), "INSTANT", null,
                List.of(new CreateStockTransferRequest.LineRequest(
                        "PRODUID00000000000000001", new BigDecimal("2"),
                        "UNITUID00000000000000CTN"))));

        assertThat(saved).hasSize(1);
        assertThat(saved.get(0).getUnitName()).isEqualTo("Carton");
        // What the storekeeper typed stays as typed …
        assertThat(saved.get(0).getQtyTransferred()).isEqualByComparingTo("2");
        // … and what actually moves is 24 pieces.
        assertThat(saved.get(0).getQtyTransferredBase()).isEqualByComparingTo("24");
        // 24 x 1500, not 2 x 1500.
        assertThat(saved.get(0).getValueAmount()).isEqualByComparingTo("36000.00");
    }

    /** An omitted unit means the base unit — what every transfer meant before units were selectable. */
    @Test
    void create_treatsAnAbsentUnitAsTheBaseUnit() {
        List<StockTransferLine> saved = stubCreate(new BigDecimal("1500.00"));

        service.create(new CreateStockTransferRequest(
                "SRCLOCUID0000000000000001", "DSTLOCUID0000000000000001",
                LocalDate.now(), "INSTANT", null,
                List.of(new CreateStockTransferRequest.LineRequest(
                        "PRODUID00000000000000001", new BigDecimal("4"), null))));

        assertThat(saved.get(0).getUnitName()).isEqualTo("Bottle");
        assertThat(saved.get(0).getQtyTransferredBase()).isEqualByComparingTo("4");
    }

    /**
     * An unrecognised unit is REFUSED, never quietly treated as the base unit. Defaulting would
     * accept "2 cartons" and move 2 bottles, and nobody would find out until a stock count came up
     * short weeks later.
     */
    @Test
    void create_refusesAUnitThatIsNotTheBaseUnitOrAConfiguredPack() {
        stubUpToProductLookup();
        when(productService.listBulkPacks("PRODUID00000000000000001")).thenReturn(List.of());

        assertThatThrownBy(() -> service.create(new CreateStockTransferRequest(
                "SRCLOCUID0000000000000001", "DSTLOCUID0000000000000001",
                LocalDate.now(), "INSTANT", null,
                List.of(new CreateStockTransferRequest.LineRequest(
                        "PRODUID00000000000000001", new BigDecimal("2"),
                        "UNITUID0000000000000BOGUS")))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Choose the item's own unit or one of its pack sizes");
    }

    // -------------------------------------------------------------------------
    // Unit cost on the read model (Kilimanjaro 2026-09-13: "bado haionyeshi unit price")
    // -------------------------------------------------------------------------

    /**
     * The printed note showed a total but no price per item. The price has to be per whatever the
     * line is COUNTED in — a carton line priced per bottle beside a carton quantity is a document
     * that does not add up.
     */
    @Test
    void toDto_pricesOneOfWhateverTheLineIsCountedIn() {
        StockTransfer t = transferWithId(700L, "STUID00000000000000000001");
        when(transfers.findByUid("STUID00000000000000000001")).thenReturn(Optional.of(t));
        when(transferLines.findByStockTransferIdOrderByLineNoAsc(700L)).thenReturn(List.of(
                // 2 cartons, 36,000 in total -> 18,000 a carton (not 1,500 a bottle).
                lineOf(new BigDecimal("2"), new BigDecimal("24"), new BigDecimal("36000.0000"))));

        StockTransferDto dto = service.getByUid("STUID00000000000000000001");

        assertThat(dto.lines().get(0).unitCost()).isEqualByComparingTo("18000");
        assertThat(dto.lines().get(0).valueAmount()).isEqualByComparingTo("36000.0000");
    }

    /** An uncosted line has no price either — null, never 0.00, which would read as "free". */
    @Test
    void toDto_leavesThePriceUnknownWhenTheLineWasNeverCosted() {
        StockTransfer t = transferWithId(700L, "STUID00000000000000000001");
        when(transfers.findByUid("STUID00000000000000000001")).thenReturn(Optional.of(t));
        when(transferLines.findByStockTransferIdOrderByLineNoAsc(700L)).thenReturn(List.of(
                lineOf(new BigDecimal("2"), new BigDecimal("24"), null)));

        StockTransferDto dto = service.getByUid("STUID00000000000000000001");

        assertThat(dto.lines().get(0).unitCost()).isNull();
    }

    private static StockTransferLine lineOf(BigDecimal qty, BigDecimal qtyBase, BigDecimal value) {
        StockTransferLine l = new StockTransferLine(
                700L, COMPANY_ID, (short) 1, 5L, "P001", "Konyagi 500ml",
                null, "Carton", qty, qtyBase, value, "TZS", 1L);
        ReflectionTestUtils.setField(l, "id", 1L);
        ReflectionTestUtils.setField(l, "uid", "STLUID0000000000000000001");
        return l;
    }

    /**
     * Stubs everything create() touches and captures the lines it saves.
     *
     * @param avgCost the running average the source stock carries, or null for never costed
     */
    private List<StockTransferLine> stubCreate(BigDecimal avgCost) {
        stubUpToProductLookup();

        StockOnHand soh = new StockOnHand(COMPANY_ID, SRC_BRANCH_ID, SRC_LOC_ID, 5L);
        ReflectionTestUtils.setField(soh, "avgCost", avgCost);
        when(onHands.findByCompanyIdAndProductId(COMPANY_ID, 5L)).thenReturn(List.of(soh));

        List<StockTransferLine> saved = new ArrayList<>();
        when(transferLines.save(any(StockTransferLine.class))).thenAnswer(inv -> {
            StockTransferLine l = inv.getArgument(0);
            saved.add(l);
            return l;
        });
        when(transferLines.findByStockTransferIdOrderByLineNoAsc(700L)).thenReturn(saved);
        return saved;
    }

    /**
     * Only what create() touches BEFORE the unit is resolved. Split out because the refusal path
     * throws there, and stubbing the save calls it never reaches trips Mockito's strict stubbing.
     */
    private void stubUpToProductLookup() {
        StockLocation src = location("Main Store");
        ReflectionTestUtils.setField(src, "id", SRC_LOC_ID);
        StockLocation dst = location("Bar Counter");
        ReflectionTestUtils.setField(dst, "id", DST_LOC_ID);
        ReflectionTestUtils.setField(dst, "branchId", DST_BRANCH_ID);

        when(locationResolver.resolveLocation("SRCLOCUID0000000000000001", COMPANY_ID))
                .thenReturn(src);
        when(locationResolver.resolveLocation("DSTLOCUID0000000000000001", COMPANY_ID))
                .thenReturn(dst);
        when(numberGenerator.nextTransfer(COMPANY_ID)).thenReturn("TRF-0001");
        when(transfers.save(any(StockTransfer.class))).thenAnswer(inv -> {
            StockTransfer t = inv.getArgument(0);
            ReflectionTestUtils.setField(t, "id", 700L);
            ReflectionTestUtils.setField(t, "uid", "STUID00000000000000000001");
            return t;
        });
        when(productService.getByUid("PRODUID00000000000000001")).thenReturn(product());
    }

    /** Base unit "Bottle" is the field under test; everything else is irrelevant padding. */
    private static ProductDto product() {
        return new ProductDto(5L, "PRODUID00000000000000001", COMPANY_ID,
                "P001", "Konyagi 500ml", null, null,
                true, true, false, false, false, null, null, "Bottle",
                null, null, null,
                null, null, null, null, null, null,
                null, null, null, null, null, null, null, null, null, null, null, false, null, null,
                false, null, null, null);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static StockTransfer transferWithId(Long id, String uid) {
        StockTransfer t = new StockTransfer(COMPANY_ID, "TRF-0001", "INSTANT",
                SRC_BRANCH_ID, SRC_LOC_ID, DST_BRANCH_ID, DST_LOC_ID,
                LocalDate.now(), null, 1L);
        ReflectionTestUtils.setField(t, "id", id);
        ReflectionTestUtils.setField(t, "uid", uid);
        return t;
    }

    /** Company param intentionally null — only name/code are read by the enrichment path. */
    private static Branch branch(String code, String name) {
        return new Branch(null, code, name);
    }

    private static StockLocation location(String name) {
        return new StockLocation(COMPANY_ID, SRC_BRANCH_ID, "LOC-01", name,
                LocationType.WAREHOUSE, true, 1L);
    }
}
