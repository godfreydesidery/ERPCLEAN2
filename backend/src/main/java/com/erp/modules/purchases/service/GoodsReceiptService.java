package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.CreateGoodsReceiptRequest;
import com.erp.modules.purchases.domain.dto.GoodsReceiptDto;
import com.erp.modules.purchases.domain.dto.VoidGoodsReceiptRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Goods Receipt lifecycle service (ADR-0011, FR-PURCH-01b/02b/07/08/09).
 */
public interface GoodsReceiptService {

    /**
     * Create a DRAFT GR against a PO, immediately receive it (DRAFT → RECEIVED), and emit
     * STOCK.RECEIVED in the same TX (ADR-0011 D-6/D-7).
     *
     * <p>Over-receipt (any line's received qty > outstanding) is rejected (BR-PURCH-10).
     * PO must be ORDERED or PARTIALLY_RECEIVED.
     */
    GoodsReceiptDto createAndReceive(CreateGoodsReceiptRequest req);

    /** Get a GR by uid; assertCanActIn on every read path. */
    GoodsReceiptDto getByUid(String uid);

    /** Paged list for a company. */
    Page<GoodsReceiptDto> list(Long companyId, String q, Pageable pageable);

    /**
     * Void a RECEIVED GR (RECEIVED → VOID): reverse PO outstanding + emit STOCK.RECEIPT.VOIDED
     * in the same TX (ADR-0011 D-6/D-7, FR-PURCH-09).
     */
    GoodsReceiptDto voidReceipt(String uid, VoidGoodsReceiptRequest req);
}
