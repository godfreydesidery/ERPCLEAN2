package com.erp.modules.sales.domain.dto;

/**
 * Outbox payload for STANDING_ORDER.GENERATED event (ADR-0029 D-8).
 * Published when the scheduler generates a SalesOrder from a standing order template.
 */
public record StandingOrderGeneratedPayload(
        String standingOrderUid,
        String salesOrderUid,
        Long companyId,
        Long branchId
) {}
