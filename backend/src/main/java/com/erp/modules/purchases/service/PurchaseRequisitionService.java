package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.CreatePurchaseRequisitionRequest;
import com.erp.modules.purchases.domain.dto.PurchaseRequisitionDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Purchase Requisition lifecycle (ADR-0027 D-3, FR-PROC-01..05).
 */
public interface PurchaseRequisitionService {

    PurchaseRequisitionDto create(CreatePurchaseRequisitionRequest req);

    PurchaseRequisitionDto getByUid(String uid);

    Page<PurchaseRequisitionDto> list(Long companyId, Pageable pageable);

    /** DRAFT → SUBMITTED; assigns requisition_number (PR-####). */
    PurchaseRequisitionDto submit(String uid);

    /** SUBMITTED → APPROVED (permission-gated fallback). */
    PurchaseRequisitionDto approve(String uid);

    /** SUBMITTED → REJECTED (permission-gated fallback). */
    PurchaseRequisitionDto reject(String uid, String reason);

    /**
     * APPROVED → CONVERTED.
     * convertToType: "PURCHASE_ORDER" or "RFQ".
     * Returns the uid of the created document.
     */
    String convert(String uid, String convertToType);

    /** DRAFT/SUBMITTED → CANCELLED. */
    PurchaseRequisitionDto cancel(String uid, String reason);
}
