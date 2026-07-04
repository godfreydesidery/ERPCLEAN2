package com.erp.modules.sales.repository;

import com.erp.modules.sales.domain.entity.FiscalReceipt;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface FiscalReceiptRepository extends JpaRepository<FiscalReceipt, Long> {

    /** One-per-invoice lookup (DB {@code uq_fiscal_receipt_invoice}) — the issue/retry anchor. */
    Optional<FiscalReceipt> findBySalesInvoiceId(Long salesInvoiceId);

    Optional<FiscalReceipt> findByUid(String uid);
}
