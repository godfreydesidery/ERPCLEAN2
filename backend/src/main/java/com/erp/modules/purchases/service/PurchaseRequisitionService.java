package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.ConvertRequisitionRequest;
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
     * Creates the target document (RFQ or Purchase Order) from the requisition's lines in the same
     * transaction, sets {@code convertedToUid}/{@code convertedToType} on the requisition, and
     * returns the uid of the created document (D-3).
     */
    String convert(String uid, ConvertRequisitionRequest req);

    /** DRAFT/SUBMITTED → CANCELLED. */
    PurchaseRequisitionDto cancel(String uid, String reason);
}
