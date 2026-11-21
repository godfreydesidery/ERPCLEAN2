package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.PosSaleReturn;
import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/** Persistence port for POS item returns (ADR-0072). Every finder carries the company predicate. */
public interface PosSaleReturnRepository extends JpaRepository<PosSaleReturn, Long> {

    Optional<PosSaleReturn> findByCompanyIdAndUid(Long companyId, String uid);

    List<PosSaleReturn> findByCompanyIdAndSalesInvoiceIdOrderByIdAsc(Long companyId, Long salesInvoiceId);

    boolean existsByCompanyIdAndSalesInvoiceId(Long companyId, Long salesInvoiceId);

    /** Cash refunded by item returns against one sale (0 when none). */
    @Query("""
            SELECT COALESCE(SUM(r.grossAmount), 0) FROM PosSaleReturn r
            WHERE r.companyId = :companyId AND r.salesInvoiceId = :invoiceId
            """)
    BigDecimal sumGrossByCompanyIdAndInvoice(@Param("companyId") Long companyId,
                                             @Param("invoiceId") Long invoiceId);

    /** Cash refunded by item returns paid from one till session (0 when none). */
    @Query("""
            SELECT COALESCE(SUM(r.grossAmount), 0) FROM PosSaleReturn r
            WHERE r.companyId = :companyId AND r.posSessionId = :sessionId
            """)
    BigDecimal sumGrossByCompanyIdAndSession(@Param("companyId") Long companyId,
                                             @Param("sessionId") Long sessionId);

    /**
     * Net and VAT given back by item returns created in [from, to), by tax band and the originating
     * invoice's stamped FX rate — the VAT return's negative for the month the refund was paid.
     */
    @Query("""
            SELECT new com.erp.modules.sales.domain.dto.PosReturnVatRowDto(
                       sl.vatStatus, i.fxRate, SUM(rl.netAmount), SUM(rl.vatAmount))
            FROM PosSaleReturnLine rl, PosSaleReturn r, SalesInvoiceLine sl, SalesInvoice i
            WHERE rl.posSaleReturnId = r.id AND sl.id = rl.salesInvoiceLineId
              AND i.id = r.salesInvoiceId
              AND r.companyId = :companyId AND r.createdAt >= :from AND r.createdAt < :to
            GROUP BY sl.vatStatus, i.fxRate
            """)
    List<com.erp.modules.sales.domain.dto.PosReturnVatRowDto> vatRowsByCompanyIdBetween(
            @Param("companyId") Long companyId,
            @Param("from") java.time.Instant from,
            @Param("to") java.time.Instant to);

    /**
     * Cash refunded by item returns created in [from, to), per branch, in the company's BASE
     * currency (face gross x the originating invoice's stamped rate) — what the BI branch-sales panel
     * subtracts from the finalised-invoice total. {@code count} is 0: a return is not a sale.
     */
    @Query("""
            SELECT new com.erp.modules.sales.domain.dto.BranchSalesAggregateDto(
                       r.branchId, SUM(r.grossAmount * COALESCE(i.fxRate, 1)), 0L)
            FROM PosSaleReturn r, SalesInvoice i
            WHERE i.id = r.salesInvoiceId
              AND r.companyId = :companyId AND r.createdAt >= :from AND r.createdAt < :to
              AND (:branchId IS NULL OR r.branchId = :branchId)
            GROUP BY r.branchId
            """)
    List<com.erp.modules.sales.domain.dto.BranchSalesAggregateDto> sumBaseGrossByCompanyIdBranch(
            @Param("companyId") Long companyId,
            @Param("from") java.time.Instant from,
            @Param("to") java.time.Instant to,
            @Param("branchId") Long branchId);

    /** The invoice ids (of those asked about) that have at least one return — one query for a list page. */
    @Query("""
            SELECT DISTINCT r.salesInvoiceId FROM PosSaleReturn r
            WHERE r.companyId = :companyId AND r.salesInvoiceId IN :invoiceIds
            """)
    List<Long> findReturnedInvoiceIdsByCompanyId(@Param("companyId") Long companyId,
                                                 @Param("invoiceIds") Collection<Long> invoiceIds);
}
