package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.stock.domain.dto.StockTransferDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.entity.StockTransfer;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.modules.stock.repository.StockTransferLineRepository;
import com.erp.modules.stock.repository.StockTransferRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.LocalDate;
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
