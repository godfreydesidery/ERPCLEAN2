package com.erp.modules.costing.domain.dto;

import com.erp.platform.common.domain.MasterStatus;

/**
 * Read DTO for a dimension value (cost centre, department, etc.) (ADR-0025 D-1).
 * id is included for Budgeting/Projects consumers who reference by id (D-8 contract).
 */
public record DimensionValueDto(
        Long id,
        String uid,
        Long companyId,
        Long dimensionId,
        String dimensionUid,
        String slot,           // DimensionSlot name — denormalised for API convenience
        String code,
        String name,
        Long parentId,
        String parentUid,
        boolean active,
        MasterStatus status
) {}
