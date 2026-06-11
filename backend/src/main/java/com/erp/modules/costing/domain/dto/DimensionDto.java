package com.erp.modules.costing.domain.dto;

import com.erp.modules.costing.domain.enums.DimensionSlot;
import com.erp.platform.common.domain.MasterStatus;

/**
 * Read DTO for a dimension type (ADR-0025 D-1).
 * id is included (Long, serialised as JSON string by the global Jackson config).
 */
public record DimensionDto(
        Long id,
        String uid,
        Long companyId,
        DimensionSlot slot,
        String code,
        String name,
        boolean builtIn,
        boolean mandatory,
        MasterStatus status
) {}
