package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ArReceiptService {

    /**
     * Record a receipt, auto-allocate oldest-first (or use manual override if provided),
     * and post the cash leg to GL synchronously (ADR-0014 D-3/D-4, FR-AR-06/07).
     * A GL failure rolls back the whole command (D-4 — correct behaviour).
     */
    ArReceiptDto recordAndAllocate(RecordReceiptRequest req);

    /**
     * Re-allocate an existing receipt (replace its allocation set).
     * Posts nothing to GL (BR-AR-12).
     */
    ArReceiptDto reallocate(String receiptUid, java.util.List<RecordReceiptRequest.AllocationLineRequest> allocations);

    /**
     * Reverse a posted receipt (ARC-04): posts the reversing journal of its cash leg, writes the
     * opposite cash-book row on the same cash/bank account, restores the invoices its allocations
     * relieved and stamps {@code reversed_at}. Refused when already reversed, when the receipt's
     * own accounting period is closed, or when withholding tax was deducted from it.
     */
    ArReceiptDto reverse(String receiptUid, String reason);

    ArReceiptDto getByUid(String uid);

    Page<ArReceiptDto> listByCompany(Long companyId, Pageable pageable);

    Page<ArReceiptDto> listByCustomer(Long companyId, Long customerId, Pageable pageable);

    /**
     * The Receipts list with its customer filter (ARC-02): the customer may be named by id or by
     * uid (resolved inside {@code companyId}); both null = every customer.
     */
    Page<ArReceiptDto> list(Long companyId, Long customerId, String customerUid, Pageable pageable);
}
