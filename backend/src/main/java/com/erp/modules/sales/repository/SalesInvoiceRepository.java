package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.dto.BranchSalesAggregateDto;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesInvoiceRepository extends JpaRepository<SalesInvoice, Long> {

    Optional<SalesInvoice> findByUid(String uid);

    Page<SalesInvoice> findByCompanyId(Long companyId, Pageable pageable);

    @Query("""
            SELECT i FROM SalesInvoice i
            WHERE i.companyId = :companyId
              AND (:q IS NULL OR
                   LOWER(i.invoiceNumber) LIKE LOWER(CONCAT('%', :q, '%')))
            """)
    Page<SalesInvoice> search(@Param("companyId") Long companyId,
                              @Param("q") String q,
                              Pageable pageable);

    /**
     * Resolves an invoice uid to its owning company id — used by {@code ScopeGuard.companyIdOf}
     * for case "invoice" (ADR-0008 D-10). Single-column JPQL projection.
     */
    @Query("SELECT i.companyId FROM SalesInvoice i WHERE i.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /**
     * Company-scoped invoice lookup by uid — used by GL's SalesPostingHandler re-read (ADR-0013 D-12).
     * Scoped in the query so no cross-tenant read is possible.
     */
    Optional<SalesInvoice> findByUidAndCompanyId(String uid, Long companyId);

    /**
     * Gross total of all FINALISED POS invoices for a session — used by PosSessionServiceImpl
     * to compute expected cash at session close (ADR-0029 D-5).
     */
    @Query("""
            SELECT COALESCE(SUM(i.grossTotalAmount), 0)
            FROM SalesInvoice i
            WHERE i.posSessionId = :sessionId
              AND i.status = 'FINALISED'
            """)
    BigDecimal sumGrossByPosSession(@Param("sessionId") Long sessionId);

    /**
     * Count of FINALISED invoices for a session — for X/Z-read reports.
     */
    @Query("""
            SELECT COUNT(i) FROM SalesInvoice i
            WHERE i.posSessionId = :sessionId
              AND i.status = 'FINALISED'
            """)
    long countByPosSession(@Param("sessionId") Long sessionId);

    /**
     * FINALISED invoices whose finalised_at falls in [periodStart, periodEnd) —
     * used by VatReturnComputationReader (ADR-0017 D-6). Company-scoped.
     */
    @Query("""
            SELECT i FROM SalesInvoice i
            WHERE i.companyId = :companyId
              AND i.status = 'FINALISED'
              AND i.finalisedAt >= :periodStart
              AND i.finalisedAt < :periodEnd
            """)
    List<SalesInvoice> findFinalisedInPeriod(@Param("companyId") Long companyId,
                                             @Param("periodStart") Instant periodStart,
                                             @Param("periodEnd") Instant periodEnd);

    /**
     * Per-branch FINALISED invoice aggregate for a company within a finalisedAt window.
     *
     * <p>{@code branchId} is optional — pass {@code null} to aggregate across all branches.
     * Grouped by branchId; result is a constructor-expression into {@link BranchSalesAggregateDto}.
     * Used by {@code SalesByBranchQuery} (BI dashboard branch-sales panel, ADR-0037).
     */
    @Query("""
            SELECT new com.erp.modules.sales.domain.dto.BranchSalesAggregateDto(
                       i.branchId,
                       COALESCE(SUM(i.grossTotalAmount), 0),
                       COUNT(i))
            FROM SalesInvoice i
            WHERE i.companyId   = :companyId
              AND i.status      = 'FINALISED'
              AND i.finalisedAt >= :fromInstant
              AND i.finalisedAt <  :toInstant
              AND (:branchId IS NULL OR i.branchId = :branchId)
            GROUP BY i.branchId
            """)
    List<BranchSalesAggregateDto> sumFinalisedByBranch(
            @Param("companyId")   Long    companyId,
            @Param("fromInstant") Instant fromInstant,
            @Param("toInstant")   Instant toInstant,
            @Param("branchId")    Long    branchId);
}
