package com.erp.modules.ap.repository;

import com.erp.modules.ap.domain.entity.ApPayment;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApPaymentRepository extends JpaRepository<ApPayment, Long> {

    Optional<ApPayment> findByUid(String uid);

    /** ScopeGuard projection (ADR-0015 D-12). */
    @Query("SELECT p.companyId FROM ApPayment p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Optional<ApPayment> findByCompanyIdAndUid(Long companyId, String uid);

    Page<ApPayment> findByCompanyId(Long companyId, Pageable pageable);

    Page<ApPayment> findByCompanyIdAndSupplierId(Long companyId, Long supplierId, Pageable pageable);

    /**
     * Update the payment total after all allocations are summed in a payment run.
     * Uses JPQL update to bypass the {@code updatable = false} JPA hint on {@code amount}.
     */
    @Modifying
    @Query("UPDATE ApPayment p SET p.amount = :amount WHERE p.id = :id")
    void updateAmount(@Param("id") Long id, @Param("amount") BigDecimal amount);

    // -------------------------------------------------------------------------
    // D-9: on-account (unallocated) queries
    // -------------------------------------------------------------------------

    /**
     * Sum of unallocated_amount across all payments for a supplier.
     * Used by ApBalanceService to net prepayments from the outstanding AP balance (D-9).
     */
    @Query("""
            SELECT COALESCE(SUM(p.unallocatedAmount), 0)
            FROM ApPayment p
            WHERE p.companyId = :companyId
              AND p.supplierId = :supplierId
            """)
    BigDecimal sumUnallocatedByCompanyAndSupplier(@Param("companyId") Long companyId,
                                                   @Param("supplierId") Long supplierId);

    /**
     * Sum of unallocated_amount across all payments for a company.
     * Used in AP reconciliation to net on-account balances from GL 2100.
     */
    @Query("""
            SELECT COALESCE(SUM(p.unallocatedAmount), 0)
            FROM ApPayment p
            WHERE p.companyId = :companyId
            """)
    BigDecimal sumUnallocatedByCompany(@Param("companyId") Long companyId);

    /**
     * On-account (unallocated) payments for a supplier — used in apply-credit UI.
     */
    @Query("""
            SELECT p FROM ApPayment p
            WHERE p.companyId = :companyId
              AND p.supplierId = :supplierId
              AND p.unallocatedAmount > 0
            ORDER BY p.paymentDate ASC, p.id ASC
            """)
    List<ApPayment> findOnAccountByCompanyAndSupplier(@Param("companyId") Long companyId,
                                                       @Param("supplierId") Long supplierId);
}
