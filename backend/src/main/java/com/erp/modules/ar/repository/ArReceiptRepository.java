package com.erp.modules.ar.repository;

import com.erp.modules.ar.domain.entity.ArReceipt;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArReceiptRepository extends JpaRepository<ArReceipt, Long> {

    Optional<ArReceipt> findByUid(String uid);

    Optional<ArReceipt> findByCompanyIdAndUid(Long companyId, String uid);

    /** ScopeGuard support. */
    @Query("SELECT r.companyId FROM ArReceipt r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<ArReceipt> findByCompanyId(Long companyId, Pageable pageable);

    Page<ArReceipt> findByCompanyIdAndCustomerId(Long companyId, Long customerId, Pageable pageable);

    /** On-account (unallocated) receipts for a customer. */
    @Query("""
            SELECT r FROM ArReceipt r
            WHERE r.companyId = :companyId
              AND r.customerId = :customerId
              AND r.unallocatedAmount > 0
            """)
    List<ArReceipt> findOnAccountByCompanyAndCustomer(@Param("companyId") Long companyId,
                                                       @Param("customerId") Long customerId);

    /** Sum of unallocated_amount for a company — used in reconciliation. */
    @Query("""
            SELECT COALESCE(SUM(r.unallocatedAmount), 0)
            FROM ArReceipt r
            WHERE r.companyId = :companyId
            """)
    BigDecimal sumUnallocatedByCompany(@Param("companyId") Long companyId);

    /** Sum of unallocated_amount for a customer — used in customer balance (D-7). */
    @Query("""
            SELECT COALESCE(SUM(r.unallocatedAmount), 0)
            FROM ArReceipt r
            WHERE r.companyId = :companyId
              AND r.customerId = :customerId
            """)
    BigDecimal sumUnallocatedByCompanyAndCustomer(@Param("companyId") Long companyId,
                                                   @Param("customerId") Long customerId);

    /** Recent receipts for a customer statement. */
    @Query("""
            SELECT r FROM ArReceipt r
            WHERE r.companyId = :companyId
              AND r.customerId = :customerId
            ORDER BY r.receiptDate DESC, r.id DESC
            """)
    List<ArReceipt> findRecentByCompanyAndCustomer(@Param("companyId") Long companyId,
                                                    @Param("customerId") Long customerId,
                                                    Pageable pageable);
}
