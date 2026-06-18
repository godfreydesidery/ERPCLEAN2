package com.erp.modules.ar.repository;

import com.erp.modules.ar.domain.entity.ArCreditNote;
import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ArCreditNoteRepository extends JpaRepository<ArCreditNote, Long> {

    Optional<ArCreditNote> findByUid(String uid);

    Optional<ArCreditNote> findByCompanyIdAndUid(Long companyId, String uid);

    /** ScopeGuard support. */
    @Query("SELECT n.companyId FROM ArCreditNote n WHERE n.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<ArCreditNote> findByCompanyId(Long companyId, Pageable pageable);

    List<ArCreditNote> findByCompanyIdAndCustomerId(Long companyId, Long customerId);

    List<ArCreditNote> findByArInvoiceId(Long arInvoiceId);

    /**
     * Sum of unapplied_amount across all credit notes for a customer.
     * Netted against outstanding in ArBalanceService (ADR-0040 D-6).
     */
    @Query("""
            SELECT COALESCE(SUM(n.unappliedAmount), 0)
            FROM ArCreditNote n
            WHERE n.companyId = :companyId
              AND n.customerId = :customerId
            """)
    BigDecimal sumUnappliedByCompanyAndCustomer(@Param("companyId") Long companyId,
                                                @Param("customerId") Long customerId);

    /**
     * Sum of unapplied_amount across all credit notes for a company.
     * Netted in ArReconciliationQuery (ADR-0040 D-6).
     */
    @Query("""
            SELECT COALESCE(SUM(n.unappliedAmount), 0)
            FROM ArCreditNote n
            WHERE n.companyId = :companyId
            """)
    BigDecimal sumUnappliedByCompany(@Param("companyId") Long companyId);
}
