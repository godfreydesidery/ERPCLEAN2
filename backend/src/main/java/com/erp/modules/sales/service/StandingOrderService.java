package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.CreateStandingOrderRequest;
import com.erp.modules.sales.domain.dto.StandingOrderDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Standing (recurring) order lifecycle (ADR-0029 D-8).
 */
public interface StandingOrderService {

    StandingOrderDto createStanding(CreateStandingOrderRequest request);

    StandingOrderDto getStandingByUid(String uid);

    Page<StandingOrderDto> listStandings(Long companyId, Pageable pageable);

    void pauseStanding(String uid);

    void resumeStanding(String uid);

    void cancelStanding(String uid);

    /**
     * Manual trigger — generates a SalesOrder from this standing order immediately,
     * regardless of next_run_date. Useful for out-of-cycle delivery or re-generation.
     */
    StandingOrderDto triggerNow(String uid);

    /**
     * Scheduled batch: generate SOs for all ACTIVE standing orders where next_run_date <= today.
     * Called by {@code @Scheduled} every day at midnight. Emits STANDING_ORDER.GENERATED outbox events.
     */
    void generateDue();
}
