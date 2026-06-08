package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.SalesInvoice;
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
}
