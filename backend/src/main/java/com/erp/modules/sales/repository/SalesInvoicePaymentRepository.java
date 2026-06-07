package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.SalesInvoicePayment;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SalesInvoicePaymentRepository extends JpaRepository<SalesInvoicePayment, Long> {

    List<SalesInvoicePayment> findByInvoiceId(Long invoiceId);

    /**
     * Resolves a payment uid scoped to its parent invoice — SR finding F16 pattern.
     */
    Optional<SalesInvoicePayment> findByUidAndInvoiceId(String uid, Long invoiceId);
}
