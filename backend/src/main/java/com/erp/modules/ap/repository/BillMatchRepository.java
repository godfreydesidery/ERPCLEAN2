package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.BillMatch;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BillMatchRepository extends JpaRepository<BillMatch, Long> {

    List<BillMatch> findBySupplierBillId(Long supplierBillId);

    Optional<BillMatch> findBySupplierBillLineId(Long supplierBillLineId);

    /**
     * How many match rows a bill has, and how many of them actually ran BOTH legs of the 3-way
     * comparison — one row per bill, one query for a whole page.
     *
     * <p>The test for "a comparison really happened" is {@code po_unit_cost_amount IS NOT NULL AND
     * gr_received_qty IS NOT NULL}. The variance columns cannot answer it: they are NOT NULL and
     * default to 0, so an un-compared leg persists as a reassuring zero. The nullable fact columns
     * are the only durable evidence that the check ran, which is why the match engine is careful to
     * overwrite them on every re-match.
     *
     * <p>Bills with no match rows at all return NO ROW here — deliberately. The caller must decide
     * what silence means (see {@code BillComparisonState.NEVER_MATCHED}); a zero row would let
     * "never checked" arrive looking like "checked and clean".
     */
    @Query("""
            SELECT m.supplierBillId AS billId,
                   COUNT(m)         AS matchCount,
                   SUM(CASE WHEN m.poUnitCostAmount IS NOT NULL
                             AND m.grReceivedQty    IS NOT NULL
                            THEN 1L ELSE 0L END) AS comparedCount
            FROM BillMatch m
            WHERE m.supplierBillId IN :billIds
            GROUP BY m.supplierBillId
            """)
    List<BillComparisonCounts> countComparedLinesByBillIds(
            @Param("billIds") Collection<Long> billIds);

    /** Projection for {@link #countComparedLinesByBillIds(Collection)}. */
    interface BillComparisonCounts {

        Long getBillId();

        /** Match rows on the bill — 0 bills never appear (no row is returned for them). */
        Long getMatchCount();

        /** Match rows whose price AND quantity legs both ran. */
        Long getComparedCount();
    }
}
