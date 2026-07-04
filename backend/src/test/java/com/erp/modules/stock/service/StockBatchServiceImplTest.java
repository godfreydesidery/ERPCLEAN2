package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.stock.domain.dto.StockBatchDto;
import com.erp.modules.stock.domain.entity.StockBatch;
import com.erp.modules.stock.repository.StockBatchRepository;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StockBatchServiceImpl}, focused on D-2 (Goods Receipt void batch
 * reversal): {@link StockBatchService#reverseReceiptQty}.
 */
class StockBatchServiceImplTest {

    private static final Long COMPANY_ID  = 10L;
    private static final Long BRANCH_ID   = 20L;
    private static final Long LOCATION_ID = 30L;
    private static final Long PRODUCT_ID  = 40L;

    private StockBatchRepository batches;
    private StockBatchServiceImpl service;

    @BeforeEach
    void setUp() {
        batches = mock(StockBatchRepository.class);
        ScopeGuard scopeGuard = mock(ScopeGuard.class);
        service = new StockBatchServiceImpl(batches, scopeGuard);
    }

    // -------------------------------------------------------------------------
    // reverseReceiptQty
    // -------------------------------------------------------------------------

    @Test
    void reverseReceiptQty_existingBatch_negatesQtyOnHand() {
        StockBatch batch = new StockBatch(COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID,
                "LOT-001", LocalDate.now(), LocalDate.now().plusYears(1), 1L);
        batch.applyDelta(new BigDecimal("10"), 1L); // simulate the original receipt
        when(batches.findByCompanyIdAndBranchIdAndLocationIdAndProductIdAndLotNumber(
                COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID, "LOT-001"))
                .thenReturn(Optional.of(batch));
        when(batches.save(batch)).thenReturn(batch);

        StockBatchDto result = service.reverseReceiptQty(
                COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID, "LOT-001",
                new BigDecimal("10"), 99L);

        assertThat(result).isNotNull();
        assertThat(batch.getQtyOnHand()).isEqualByComparingTo(BigDecimal.ZERO);
        verify(batches).save(batch);
    }

    @Test
    void reverseReceiptQty_noMatchingBatch_returnsNullAndDoesNotCreateOrDelete() {
        when(batches.findByCompanyIdAndBranchIdAndLocationIdAndProductIdAndLotNumber(
                COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID, "MISSING-LOT"))
                .thenReturn(Optional.empty());

        StockBatchDto result = service.reverseReceiptQty(
                COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID, "MISSING-LOT",
                new BigDecimal("5"), 99L);

        assertThat(result).isNull();
        verify(batches, never()).save(org.mockito.ArgumentMatchers.any());
        verify(batches, never()).delete(org.mockito.ArgumentMatchers.any());
        verify(batches, never()).deleteById(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void reverseReceiptQty_zeroOrNegativeQty_throws() {
        assertThatThrownBy(() -> service.reverseReceiptQty(
                COMPANY_ID, BRANCH_ID, LOCATION_ID, PRODUCT_ID, "LOT-001",
                BigDecimal.ZERO, 99L))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
