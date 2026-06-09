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

    ArReceiptDto getByUid(String uid);

    Page<ArReceiptDto> listByCompany(Long companyId, Pageable pageable);

    Page<ArReceiptDto> listByCustomer(Long companyId, Long customerId, Pageable pageable);
}
