package com.erp.modules.manufacturing.domain.dto;

import java.math.BigDecimal;

/** Response DTO for a work-order operation (ADR-0035 D-3). */
public record WorkOrderOperationDto(
        Long id,
        String uid,
        Long workOrderId,
        int seqNo,
        String description,
        String workCentre,
        BigDecimal labourAmount,
        BigDecimal overheadAmount,
        boolean applied
) {}
