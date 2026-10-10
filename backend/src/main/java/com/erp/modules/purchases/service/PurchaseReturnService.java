package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.CreatePurchaseReturnRequest;
import com.erp.modules.purchases.domain.dto.PurchaseReturnDto;
import com.erp.modules.purchases.domain.dto.PurchaseReturnPrintDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Purchase Return lifecycle (ADR-0027 D-7, FR-PROC-19..22).
 */
public interface PurchaseReturnService {

    PurchaseReturnDto create(CreatePurchaseReturnRequest req);

    PurchaseReturnDto getByUid(String uid);

    Page<PurchaseReturnDto> list(Long companyId, Pageable pageable);

    /**
     * DRAFT → CONFIRMED.
     * Publishes PURCHASE.RETURNED outbox event (stock handler reverses qty + GL).
     * Raises AP debit note via ApDebitNoteService.
     * Updates GR line returned_qty_in_base.
     */
    PurchaseReturnDto confirm(String uid);

    /**
     * The printed purchase return / debit note's read model (stream-only print/export). Tenant-checked
     * against the return's own company; read-only — nothing is stored.
     */
    PurchaseReturnPrintDto printByUid(String uid);
}
