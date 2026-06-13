package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.CaptureSupplierQuoteRequest;
import com.erp.modules.purchases.domain.dto.SupplierQuoteDto;
import java.math.BigDecimal;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Supplier Quote capture (ADR-0027 D-4, FR-PROC-08..10).
 */
public interface SupplierQuoteService {

    /** Capture a supplier's quote against an RFQ. */
    SupplierQuoteDto capture(CaptureSupplierQuoteRequest req);

    SupplierQuoteDto getByUid(String uid);

    Page<SupplierQuoteDto> listByRfq(String rfqUid, Pageable pageable);

    /** Last quoted unit cost for a product/supplier pair (most recent quote, any status). */
    BigDecimal lastQuotedUnitCost(Long companyId, Long supplierId, Long productId);
}
