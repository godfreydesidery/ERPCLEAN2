package com.erp.modules.costing.domain.dto;

/**
 * Request DTO to create a dimension value (cost centre / department / etc.) (ADR-0025 D-1).
 * {@code dimensionUid} identifies the owning dimension; {@code parentUid} is optional (null = root).
 */
public record CreateDimensionValueRequest(
        String dimensionUid,
        String code,
        String name,
        String parentUid       // nullable — null means a root value
) {}
