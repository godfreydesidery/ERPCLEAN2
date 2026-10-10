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
     * SAL-10: the invoice list's filters. Every parameter is bound non-null (no null-typed binds
     * reach Postgres): {@code anyText}/{@code anyStatus} switch a filter off, and the date window
     * defaults to the whole of time. {@code pattern} is a lower-cased LIKE pattern matched against
     * the invoice number OR the customer's name (the "Customer" search used to match numbers
     * only). The window applies to the creation time.
     */
    @Query("""
            SELECT i FROM SalesInvoice i
            WHERE i.companyId = :companyId
              AND (:anyStatus = true OR i.status IN :statuses)
              AND i.createdAt >= :from
              AND i.createdAt <  :to
              AND (:anyText = true
                   OR LOWER(i.invoiceNumber) LIKE :pattern
                   OR i.customerId IN (SELECT c.id FROM Customer c
                                       WHERE c.companyId = :companyId
                                         AND LOWER(c.displayName) LIKE :pattern))
            """)
    Page<SalesInvoice> searchFiltered(@Param("companyId") Long companyId,
                                      @Param("anyStatus") boolean anyStatus,
                                      @Param("statuses") java.util.Collection<
                                              com.erp.modules.sales.domain.enums.InvoiceStatus> statuses,
                                      @Param("from") Instant from,
                                      @Param("to") Instant to,
                                      @Param("anyText") boolean anyText,
                                      @Param("pattern") String pattern,
                                      Pageable pageable);

    /**
     * The till's "Today's sales": POS invoices of one branch finalised at or after {@code from},
     * newest first. VOID is kept on purpose — a reversed sale still happened today and the till
     * shows it as reversed. All parameters are required, so no null-typed bind can reach Postgres.
     */
    @Query("""
            SELECT i FROM SalesInvoice i
            WHERE i.companyId = :companyId
              AND i.branchId  = :branchId
              AND i.origin    = com.erp.modules.sales.domain.enums.DocumentOrigin.POS
              AND i.status IN (com.erp.modules.sales.domain.enums.InvoiceStatus.FINALISED,
                               com.erp.modules.sales.domain.enums.InvoiceStatus.VOID)
              AND i.finalisedAt >= :from
            ORDER BY i.finalisedAt DESC
            """)
    Page<SalesInvoice> findPosSalesSince(@Param("companyId") Long companyId,
                                         @Param("branchId") Long branchId,
                                         @Param("from") Instant from,
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
     * Gross turnover of all FINALISED POS invoices for a session, across every tender type
     * (ADR-0029 D-5). Reporting/turnover figure only — do NOT use this for the expected-cash
     * drawer arithmetic (card/mobile-money/cheque tenders never enter the till). Use
     * {@link com.erp.modules.sales.repository.SalesInvoicePaymentRepository#sumCashTenderByPosSession}
     * for that.
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
     * Invoices FINALISED in [periodStart, periodEnd) — by finalised_at — used by
     * VatReturnComputationReader (ADR-0017 D-6). Company-scoped.
     *
     * <p>ACC-24: an invoice voided LATER is still counted here. It was a supply of the month it was
     * finalised in — its SALES journal credited VAT Payable in that month and stays there — so the
     * month it was finalised keeps its output VAT, and the void is a negative in the month it
     * happened ({@link #findVoidedInPeriod}). Before this, a recompute of a still-DRAFT return
     * silently dropped a later-voided invoice from its original month while the GL kept it.
     */
    @Query("""
            SELECT i FROM SalesInvoice i
            WHERE i.companyId = :companyId
              AND i.status IN ('FINALISED', 'VOID')
              AND i.finalisedAt >= :periodStart
              AND i.finalisedAt < :periodEnd
            """)
    List<SalesInvoice> findFinalisedInPeriod(@Param("companyId") Long companyId,
                                             @Param("periodStart") Instant periodStart,
                                             @Param("periodEnd") Instant periodEnd);

    /**
     * ACC-24: previously-finalised invoices VOIDED in [periodStart, periodEnd) — by voided_at. Their
     * output VAT is a negative on the return of the month the void happened (the month the GL
     * reversal debits VAT Payable). Company-scoped.
     */
    @Query("""
            SELECT i FROM SalesInvoice i
            WHERE i.companyId = :companyId
              AND i.status = 'VOID'
              AND i.finalisedAt IS NOT NULL
              AND i.voidedAt >= :periodStart
              AND i.voidedAt < :periodEnd
            ORDER BY i.voidedAt, i.id
            """)
    List<SalesInvoice> findVoidedInPeriod(@Param("companyId") Long companyId,
                                          @Param("periodStart") Instant periodStart,
                                          @Param("periodEnd") Instant periodEnd);

    /**
     * Per-branch FINALISED invoice aggregate for a company within a finalisedAt window.
     *
     * <p>{@code branchId} is optional — pass {@code null} to aggregate across all branches.
     * Grouped by branchId; result is a constructor-expression into {@link BranchSalesAggregateDto}.
     * Used by {@code SalesByBranchQuery} (BI dashboard branch-sales panel, ADR-0037).
     *
     * <p>The total is in the company's BASE currency: {@code baseGrossTotalAmount} is the gross
     * converted at the rate stamped at finalise (ADR-0036 D-4). Summing {@code grossTotalAmount}
     * instead added a USD invoice into the TZS panel at 1:1. Every FINALISED row carries a base
     * amount (stamped at finalise, back-filled = face for pre-FX rows); the COALESCE only guards a
     * row that somehow lacks one, where face is the best figure available.
     */
    @Query("""
            SELECT new com.erp.modules.sales.domain.dto.BranchSalesAggregateDto(
                       i.branchId,
                       COALESCE(SUM(COALESCE(i.baseGrossTotalAmount, i.grossTotalAmount)), 0),
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
