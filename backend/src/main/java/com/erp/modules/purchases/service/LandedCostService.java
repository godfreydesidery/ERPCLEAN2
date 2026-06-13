package com.erp.modules.purchases.service;

import com.erp.modules.purchases.domain.dto.CreateLandedCostRequest;
import com.erp.modules.purchases.domain.dto.LandedCostDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Landed Cost lifecycle (ADR-0027 D-5, FR-PROC-13..16).
 */
public interface LandedCostService {

    LandedCostDto create(CreateLandedCostRequest req);

    LandedCostDto getByUid(String uid);

    Page<LandedCostDto> list(Long companyId, Pageable pageable);

    /**
     * DRAFT → CONFIRMED.
     * Computes allocations per GR line (by basis), persists LandedCostAllocation rows,
     * publishes LANDED_COST.ALLOCATED outbox event, updates totalChargeAmount.
     */
    LandedCostDto confirm(String uid);
}
