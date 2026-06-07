package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SalesInvoiceLineRepository extends JpaRepository<SalesInvoiceLine, Long> {

    List<SalesInvoiceLine> findByInvoiceIdOrderByLineNo(Long invoiceId);

    /** Count of lines on an invoice — used to determine next line_no and check non-empty at finalise. */
    int countByInvoiceId(Long invoiceId);

    /**
     * Resolves a line uid scoped to its parent invoice — SR finding F16 pattern.
     * Ensures a line from another invoice cannot be addressed via a different invoice's URL.
     */
    Optional<SalesInvoiceLine> findByUidAndInvoiceId(String uid, Long invoiceId);

    /** Max line_no on an invoice — used to assign the next ordinal. */
    @Query("SELECT COALESCE(MAX(l.lineNo), 0) FROM SalesInvoiceLine l WHERE l.invoice.id = :invoiceId")
    int findMaxLineNo(@Param("invoiceId") Long invoiceId);
}
