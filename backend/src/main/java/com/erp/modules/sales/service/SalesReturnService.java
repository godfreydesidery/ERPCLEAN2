package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.CreateSalesReturnRequest;
import com.erp.modules.sales.domain.dto.SalesReturnDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Sales return / RMA lifecycle service (ADR-0021 D-11, Stage 2).
 *
 * <p>A return is always created in CONFIRMED status (v1 single-step posture):
 * stock-in + COGS-reversal event + credit note all happen on create.
 */
public interface SalesReturnService {

    /**
     * Create a sales return against a delivery (FR-SO-14, BR-SO-11/13).
     *
     * <p>On create:
     * <ol>
     *   <li>Guards: qty_returned_base ≤ delivered − already_returned per delivery line.</li>
     *   <li>Saves SalesReturn + SalesReturnLines.</li>
     *   <li>Increments delivery_line.returned_qty_base.</li>
     *   <li>Publishes DELIVERY.RETURNED (stock-in + COGS reversal via handler).</li>
     *   <li>Raises a credit note via ArCreditNoteService (origin=RETURN) synchronously.</li>
     * </ol>
     */
    SalesReturnDto create(CreateSalesReturnRequest req);

    SalesReturnDto getByUid(String uid);

    Page<SalesReturnDto> listByCompany(Long companyId, Pageable pageable);

    Page<SalesReturnDto> listByDelivery(String deliveryUid, Pageable pageable);
}
