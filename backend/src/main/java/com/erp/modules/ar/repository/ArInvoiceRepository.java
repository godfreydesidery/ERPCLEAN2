package com.erp.modules.ar.repository;

import com.erp.modules.ar.domain.entity.ArInvoice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;

public interface ArInvoiceRepository extends JpaRepository<ArInvoice, Long> {

    Optional<ArInvoice> findByUid(String uid);

    Optional<ArInvoice> findByCompanyIdAndUid(Long companyId, String uid);

    /** ScopeGuard support (ADR-0014 D-12). */
    @Query("SELECT i.companyId FROM ArInvoice i WHERE i.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /** Idempotency: is there already an open item for this source invoice? */
    @Query("SELECT i FROM ArInvoice i WHERE i.companyId = :companyId AND i.sourceInvoiceUid = :sourceInvoiceUid")
    Optional<ArInvoice> findBySalesInvoiceUid(@Param("companyId") Long companyId,
                                               @Param("sourceInvoiceUid") String sourceInvoiceUid);

    Page<ArInvoice> findByCompanyId(Long companyId, Pageable pageable);

    Page<ArInvoice> findByCompanyIdAndCustomerId(Long companyId, Long customerId, Pageable pageable);

    /**
     * Open items for oldest-first allocation + ageing. Ordered by due_date ASC.
     * Uses ix_ar_invoices_open partial index.
     */
    @Query("""
            SELECT i FROM ArInvoice i
            WHERE i.companyId = :companyId
              AND i.customerId = :customerId
              AND i.status IN ('OPEN','PARTIAL')
            ORDER BY i.dueDate ASC, i.id ASC
            """)
    List<ArInvoice> findOpenByCompanyAndCustomerOrderByDueDate(
            @Param("companyId") Long companyId,
            @Param("customerId") Long customerId);

    /**
     * Pessimistic write lock on open items for concurrent allocation (NFR-AR-02).
     * Used when building the allocation set to prevent over-settlement.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT i FROM ArInvoice i
            WHERE i.companyId = :companyId
              AND i.customerId = :customerId
              AND i.status IN ('OPEN','PARTIAL')
            ORDER BY i.dueDate ASC, i.id ASC
            """)
    List<ArInvoice> findOpenForUpdateByCompanyAndCustomer(
            @Param("companyId") Long companyId,
            @Param("customerId") Long customerId);

    /** Sum of outstanding_amount for open/partial invoices — used in balance calculation (D-7). */
    @Query("""
            SELECT COALESCE(SUM(i.outstandingAmount), 0)
            FROM ArInvoice i
            WHERE i.companyId = :companyId
              AND i.customerId = :customerId
              AND i.status IN ('OPEN','PARTIAL')
            """)
    BigDecimal sumOutstandingByCompanyAndCustomer(@Param("companyId") Long companyId,
                                                   @Param("customerId") Long customerId);

    /** Sum of all open outstanding across a company (reconciliation sub-ledger total). */
    @Query("""
            SELECT COALESCE(SUM(i.outstandingAmount), 0)
            FROM ArInvoice i
            WHERE i.companyId = :companyId
              AND i.status IN ('OPEN','PARTIAL')
            """)
    BigDecimal sumOutstandingByCompany(@Param("companyId") Long companyId);

    /** Open/partial invoices for a customer ordered by due date — for statement. */
    @Query("""
            SELECT i FROM ArInvoice i
            WHERE i.companyId = :companyId
              AND i.customerId = :customerId
              AND i.status IN ('OPEN','PARTIAL')
            ORDER BY i.dueDate ASC
            """)
    List<ArInvoice> findOpenForStatement(@Param("companyId") Long companyId,
                                          @Param("customerId") Long customerId);

    /** Status check only. */
    boolean existsByCompanyIdAndUid(Long companyId, String uid);

    // --- FX revaluation aggregate (ADR-0036 D-6) ---

    /**
     * Returns all OPEN/PARTIAL AR invoices in a foreign currency (currency != baseCurrency)
     * for the given company — used by the period-end FX revaluation run to compute the
     * unrealized adjustment per currency. Caller groups by currency in-service.
     */
    @Query("""
            SELECT i FROM ArInvoice i
            WHERE i.companyId = :companyId
              AND i.status IN ('OPEN','PARTIAL')
              AND i.currency <> :baseCurrency
            """)
    List<ArInvoice> findOpenForeignForRevaluation(
            @Param("companyId") Long companyId,
            @Param("baseCurrency") String baseCurrency);

    // --- notifications scanner (ADR-0024 D-7): invoice-overdue scan ---

    /**
     * Open/partial invoices past due date for a company — used by {@code NotificationScanner}
     * (ADR-0024 D-7). Backed by {@code ix_ar_invoices_overdue_scan} (V26).
     */
    @Query("""
            SELECT i FROM ArInvoice i
            WHERE i.companyId = :companyId
              AND i.status IN ('OPEN','PARTIAL')
              AND i.dueDate < :today
            ORDER BY i.dueDate ASC
            """)
    List<ArInvoice> findOverdueByCompany(
            @Param("companyId") Long companyId,
            @Param("today") java.time.LocalDate today);
}
