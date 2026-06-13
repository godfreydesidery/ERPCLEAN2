package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.CreateRfqRequest;
import com.erp.modules.purchases.domain.dto.RfqDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Request for Quotation lifecycle (ADR-0027 D-4, FR-PROC-06..11).
 */
public interface RfqService {

    RfqDto create(CreateRfqRequest req);

    RfqDto getByUid(String uid);

    Page<RfqDto> list(Long companyId, Pageable pageable);

    /** DRAFT → SENT (locks lines, notifies suppliers). */
    RfqDto send(String uid);

    /**
     * SENT → AWARDED.
     * Awards the winning quote; auto-creates a PO from the awarded quote.
     * Returns the updated RFQ with awardedPoUid populated.
     */
    RfqDto award(String uid, String winningQuoteUid);

    /** DRAFT/SENT → CANCELLED. */
    RfqDto cancel(String uid);
}
