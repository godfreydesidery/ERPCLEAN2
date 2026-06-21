package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link StockLeafCostResolver} — regression for MANUFACTURING-024.
 *
 * <p>Proves that when a product is stocked at multiple locations within the same branch,
 * the resolver returns a weighted-average cost rather than throwing
 * {@code IncorrectResultSizeDataAccessException}.
 */
class StockLeafCostResolverTest {

    private StockOnHandRepository    repo;
    private StockLeafCostResolver    resolver;

    private static final Long COMPANY_ID = 1L;
    private static final Long BRANCH_ID  = 2L;
    private static final Long PRODUCT_A  = 100L;
    private static final Long PRODUCT_B  = 200L;

    @BeforeEach
    void setUp() {
        repo     = mock(StockOnHandRepository.class);
        resolver = new StockLeafCostResolver(repo);
    }

    // ── empty / null guards ──────────────────────────────────────────────────

    @Test
    void avgCosts_emptyProductIds_returnsEmptyMap() {
        Map<Long, BigDecimal> result = resolver.avgCosts(COMPANY_ID, BRANCH_ID, List.of());
        assertThat(result).isEmpty();
        verify(repo, never()).findAllByCompanyIdAndBranchIdAndProductId(anyLong(), anyLong(), anyLong());
    }

    @Test
    void avgCosts_nullProductIds_returnsEmptyMap() {
        Map<Long, BigDecimal> result = resolver.avgCosts(COMPANY_ID, BRANCH_ID, null);
        assertThat(result).isEmpty();
    }

    // ── single-location product ──────────────────────────────────────────────

    @Test
    void avgCosts_singleLocationWithCost_returnsThatCost() {
        StockOnHand soh = soh(new BigDecimal("5"), new BigDecimal("10.00"));
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_A))
                .thenReturn(List.of(soh));

        Map<Long, BigDecimal> result = resolver.avgCosts(COMPANY_ID, BRANCH_ID, List.of(PRODUCT_A));

        assertThat(result).containsKey(PRODUCT_A);
        assertThat(result.get(PRODUCT_A)).isEqualByComparingTo("10.0000");
    }

    @Test
    void avgCosts_singleLocationNullAvgCost_absentFromResult() {
        StockOnHand soh = soh(new BigDecimal("5"), null);
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_A))
                .thenReturn(List.of(soh));

        Map<Long, BigDecimal> result = resolver.avgCosts(COMPANY_ID, BRANCH_ID, List.of(PRODUCT_A));

        assertThat(result).doesNotContainKey(PRODUCT_A);
    }

    @Test
    void avgCosts_noOnHandRows_productAbsentFromResult() {
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_A))
                .thenReturn(List.of());

        Map<Long, BigDecimal> result = resolver.avgCosts(COMPANY_ID, BRANCH_ID, List.of(PRODUCT_A));

        assertThat(result).doesNotContainKey(PRODUCT_A);
    }

    // ── MANUFACTURING-024: multi-location weighted-average ───────────────────

    @Test
    void avgCosts_twoLocations_returnsWeightedAverage() {
        // Location A: 10 units @ 8.00 = value 80
        // Location B:  5 units @ 14.00 = value 70
        // Weighted avg = (80 + 70) / (10 + 5) = 150 / 15 = 10.0000
        StockOnHand locA = soh(new BigDecimal("10"), new BigDecimal("8.00"));
        StockOnHand locB = soh(new BigDecimal("5"),  new BigDecimal("14.00"));
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_A))
                .thenReturn(List.of(locA, locB));

        Map<Long, BigDecimal> result = resolver.avgCosts(COMPANY_ID, BRANCH_ID, List.of(PRODUCT_A));

        assertThat(result).containsKey(PRODUCT_A);
        assertThat(result.get(PRODUCT_A)).isEqualByComparingTo("10.0000");
    }

    @Test
    void avgCosts_threeLocations_weightedAverageCorrect() {
        // Loc1: 20 units @ 5.00  → 100
        // Loc2: 10 units @ 10.00 → 100
        // Loc3: 70 units @ 2.00  → 140
        // Sum value = 340, sum qty = 100 → avg = 3.4000
        StockOnHand loc1 = soh(new BigDecimal("20"), new BigDecimal("5.00"));
        StockOnHand loc2 = soh(new BigDecimal("10"), new BigDecimal("10.00"));
        StockOnHand loc3 = soh(new BigDecimal("70"), new BigDecimal("2.00"));
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_A))
                .thenReturn(List.of(loc1, loc2, loc3));

        Map<Long, BigDecimal> result = resolver.avgCosts(COMPANY_ID, BRANCH_ID, List.of(PRODUCT_A));

        assertThat(result.get(PRODUCT_A)).isEqualByComparingTo("3.4000");
    }

    @Test
    void avgCosts_oneLocationHasCostTheOtherNull_weightedByCostedRowOnly() {
        // Loc1: 10 units @ 12.00 (costed)
        // Loc2: 5 units, null cost (not yet received — excluded from avg)
        // Result = just 12.0000 from loc1 (total positive qty = 10, value = 120)
        StockOnHand loc1 = soh(new BigDecimal("10"), new BigDecimal("12.00"));
        StockOnHand loc2 = soh(new BigDecimal("5"),  null);
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_A))
                .thenReturn(List.of(loc1, loc2));

        Map<Long, BigDecimal> result = resolver.avgCosts(COMPANY_ID, BRANCH_ID, List.of(PRODUCT_A));

        assertThat(result.get(PRODUCT_A)).isEqualByComparingTo("12.0000");
    }

    @Test
    void avgCosts_allLocationsHaveZeroQtyWithCost_fallsBackToUnweightedMean() {
        // Edge case: all costed rows have qty = 0 (stock fully consumed).
        // Weighted avg would be 0/0 → fallback to simple mean.
        StockOnHand loc1 = soh(BigDecimal.ZERO, new BigDecimal("8.00"));
        StockOnHand loc2 = soh(BigDecimal.ZERO, new BigDecimal("12.00"));
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_A))
                .thenReturn(List.of(loc1, loc2));

        Map<Long, BigDecimal> result = resolver.avgCosts(COMPANY_ID, BRANCH_ID, List.of(PRODUCT_A));

        // Unweighted mean = (8 + 12) / 2 = 10.0000
        assertThat(result.get(PRODUCT_A)).isEqualByComparingTo("10.0000");
    }

    // ── batch: multiple products in one call ─────────────────────────────────

    @Test
    void avgCosts_batchTwoProducts_returnsBothEntries() {
        StockOnHand sohA = soh(new BigDecimal("4"), new BigDecimal("25.00"));
        StockOnHand sohB = soh(new BigDecimal("8"), new BigDecimal("5.00"));
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_A))
                .thenReturn(List.of(sohA));
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_B))
                .thenReturn(List.of(sohB));

        Map<Long, BigDecimal> result = resolver.avgCosts(
                COMPANY_ID, BRANCH_ID, List.of(PRODUCT_A, PRODUCT_B));

        assertThat(result).hasSize(2);
        assertThat(result.get(PRODUCT_A)).isEqualByComparingTo("25.0000");
        assertThat(result.get(PRODUCT_B)).isEqualByComparingTo("5.0000");
    }

    @Test
    void avgCosts_oneProductMissingCost_onlyPresentProductReturned() {
        StockOnHand sohA = soh(new BigDecimal("4"), new BigDecimal("25.00"));
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_A))
                .thenReturn(List.of(sohA));
        when(repo.findAllByCompanyIdAndBranchIdAndProductId(COMPANY_ID, BRANCH_ID, PRODUCT_B))
                .thenReturn(List.of()); // no rows

        Map<Long, BigDecimal> result = resolver.avgCosts(
                COMPANY_ID, BRANCH_ID, List.of(PRODUCT_A, PRODUCT_B));

        assertThat(result).containsOnlyKeys(PRODUCT_A);
    }

    // ── helper ───────────────────────────────────────────────────────────────

    /** Build a StockOnHand stub with the given quantity and avgCost. */
    private StockOnHand soh(BigDecimal qty, BigDecimal avgCost) {
        StockOnHand s = mock(StockOnHand.class);
        when(s.getQuantity()).thenReturn(qty);
        when(s.getAvgCost()).thenReturn(avgCost);
        return s;
    }
}
