package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.platform.common.money.CurrencyCode;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import jakarta.persistence.LockModeType;

public interface SupplierBillRepository extends JpaRepository<SupplierBill, Long> {

    Optional<SupplierBill> findByUid(String uid);

    /** ScopeGuard projection (ADR-0015 D-12). */
    @Query("SELECT b.companyId FROM SupplierBill b WHERE b.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Optional<SupplierBill> findByCompanyIdAndUid(Long companyId, String uid);

    /**
     * Company-scoped id lookup, for resolving a bill referenced by internal id from an already-loaded
     * aggregate (e.g. a payment allocation). Prefer this over {@code findById}: the bare finder is a
     * confused-deputy risk and is what TenantScopingRulesTest exists to prevent — the company must come
     * from the LOADED entity, never from a caller-supplied value.
     */
    Optional<SupplierBill> findByCompanyIdAndId(Long companyId, Long id);

    Page<SupplierBill> findByCompanyId(Long companyId, Pageable pageable);

    Page<SupplierBill> findByCompanyIdAndSupplierId(Long companyId, Long supplierId, Pageable pageable);

    /**
     * The bill list read, with every filter the screen offers applied in the DATABASE — filtering a
     * page in memory would silently shrink pages and mis-state the total.
     *
     * <p>Each filter is skipped when its parameter is null, the {@code (:p IS NULL OR ...)} idiom
     * used throughout this codebase. {@code uncomparedOnly} follows the same shape rather than being
     * a boolean: null means "no filter", TRUE means "only bills that were not fully checked".
     *
     * <p><b>The uncompared predicate.</b> A bill qualifies when ANY of its lines lacks a
     * {@code bill_match} row carrying both a PO unit cost and a received quantity — i.e. at least
     * one line was never actually compared against the order and the receipt. Bills with no lines at
     * all qualify too: nothing was checked there either, and the EXISTS above cannot see them.
     * Bills with no match rows at all (an AP opening balance, an unmatched draft) qualify through
     * the same NOT EXISTS — silence is not evidence.
     *
     * @param uncomparedOnly TRUE to keep only bills that were not fully compared; null for all bills
     */
    @Query(value = """
            SELECT b FROM SupplierBill b
            WHERE b.companyId = :companyId
              AND (:supplierId IS NULL OR b.supplierId = :supplierId)
              AND (:status IS NULL OR b.status = :status)
              AND (:uncomparedOnly IS NULL
                   OR EXISTS (SELECT l.id FROM SupplierBillLine l
                              WHERE l.supplierBillId = b.id
                                AND NOT EXISTS (SELECT m.id FROM BillMatch m
                                                WHERE m.supplierBillLineId = l.id
                                                  AND m.poUnitCostAmount IS NOT NULL
                                                  AND m.grReceivedQty    IS NOT NULL))
                   OR NOT EXISTS (SELECT l2.id FROM SupplierBillLine l2
                                  WHERE l2.supplierBillId = b.id))
            """,
            countQuery = """
            SELECT COUNT(b) FROM SupplierBill b
            WHERE b.companyId = :companyId
              AND (:supplierId IS NULL OR b.supplierId = :supplierId)
              AND (:status IS NULL OR b.status = :status)
              AND (:uncomparedOnly IS NULL
                   OR EXISTS (SELECT l.id FROM SupplierBillLine l
                              WHERE l.supplierBillId = b.id
                                AND NOT EXISTS (SELECT m.id FROM BillMatch m
                                                WHERE m.supplierBillLineId = l.id
                                                  AND m.poUnitCostAmount IS NOT NULL
                                                  AND m.grReceivedQty    IS NOT NULL))
                   OR NOT EXISTS (SELECT l2.id FROM SupplierBillLine l2
                                  WHERE l2.supplierBillId = b.id))
            """)
    Page<SupplierBill> search(@Param("companyId") Long companyId,
                              @Param("supplierId") Long supplierId,
                              @Param("status") SupplierBillStatus status,
                              @Param("uncomparedOnly") Boolean uncomparedOnly,
                              Pageable pageable);

    /** Open bills for payment-run selection (locked for update — no-double-pay, NFR-AP-04). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b FROM SupplierBill b
            WHERE b.companyId = :companyId
              AND b.supplierId = :supplierId
              AND b.status IN ('MATCHED','APPROVED','PARTIALLY_PAID')
              AND b.dueDate <= :dueOnOrBefore
            ORDER BY b.dueDate ASC
            """)
    List<SupplierBill> findOpenForPayment(@Param("companyId") Long companyId,
                                          @Param("supplierId") Long supplierId,
                                          @Param("dueOnOrBefore") LocalDate dueOnOrBefore);

    /** All open bills for a company (payment run, cross-supplier). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b FROM SupplierBill b
            WHERE b.companyId = :companyId
              AND b.status IN ('MATCHED','APPROVED','PARTIALLY_PAID')
              AND b.dueDate <= :dueOnOrBefore
            ORDER BY b.dueDate ASC
            """)
    List<SupplierBill> findOpenForPaymentAllSuppliers(@Param("companyId") Long companyId,
                                                       @Param("dueOnOrBefore") LocalDate dueOnOrBefore);

    /** Open bills for a specific list of uids (payment-run explicit selection, locked). */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            SELECT b FROM SupplierBill b
            WHERE b.companyId = :companyId
              AND b.uid IN :uids
              AND b.status IN ('MATCHED','APPROVED','PARTIALLY_PAID')
            """)
    List<SupplierBill> findOpenByUids(@Param("companyId") Long companyId,
                                       @Param("uids") List<String> uids);

    /** Open bills for ageing/statement (not locked). */
    @Query("""
            SELECT b FROM SupplierBill b
            WHERE b.companyId = :companyId
              AND b.supplierId = :supplierId
              AND b.status IN ('MATCHED','APPROVED','PARTIALLY_PAID')
            ORDER BY b.dueDate ASC
            """)
    List<SupplierBill> findOpenForStatement(@Param("companyId") Long companyId,
                                             @Param("supplierId") Long supplierId);

    /** Sub-ledger total for reconciliation (ADR-0015 D-7/D-8). */
    @Query("""
            SELECT COALESCE(SUM(b.outstandingAmount), 0)
            FROM SupplierBill b
            WHERE b.companyId = :companyId
              AND b.status IN ('MATCHED','APPROVED','PARTIALLY_PAID')
            """)
    java.math.BigDecimal sumOutstandingByCompany(@Param("companyId") Long companyId);

    /** Per-supplier outstanding balance. */
    @Query("""
            SELECT COALESCE(SUM(b.outstandingAmount), 0)
            FROM SupplierBill b
            WHERE b.companyId = :companyId
              AND b.supplierId = :supplierId
              AND b.status IN ('MATCHED','APPROVED','PARTIALLY_PAID')
            """)
    java.math.BigDecimal sumOutstandingBySupplier(@Param("companyId") Long companyId,
                                                   @Param("supplierId") Long supplierId);

    /** Check duplicate supplier invoice (done at service layer too, but useful for tests). */
    boolean existsByCompanyIdAndSupplierIdAndSupplierInvoiceNo(Long companyId, Long supplierId,
                                                               String supplierInvoiceNo);

    // --- FX revaluation aggregate (ADR-0036 D-6) ---

    /**
     * Returns all open AP bills in a foreign currency (currency != baseCurrency) for the
     * given company — used by the period-end FX revaluation run to compute the unrealized
     * adjustment per currency. Caller groups by currency in-service.
     */
    @Query("""
            SELECT b FROM SupplierBill b
            WHERE b.companyId = :companyId
              AND b.status IN ('MATCHED','APPROVED','PARTIALLY_PAID')
              AND b.currency <> :baseCurrency
            """)
    java.util.List<SupplierBill> findOpenForeignForRevaluation(
            @Param("companyId") Long companyId,
            @Param("baseCurrency") CurrencyCode baseCurrency);
}
