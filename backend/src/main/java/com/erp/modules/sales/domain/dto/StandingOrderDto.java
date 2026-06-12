package com.erp.modules.sales.domain.dto;

import com.erp.modules.sales.domain.enums.StandingFrequency;
import com.erp.modules.sales.domain.enums.StandingStatus;
import java.util.List;

/**
 * Response DTO for a standing order header + lines (ADR-0029 D-8).
 */
public record StandingOrderDto(
        Long id,
        String uid,
        Long companyId,
        Long branchId,
        String orderNumber,
        Long customerId,
        String currency,
        StandingFrequency frequency,
        String startDate,
        String endDate,
        String nextRunDate,
        StandingStatus status,
        String notes,
        List<StandingOrderLineDto> lines
) {}
