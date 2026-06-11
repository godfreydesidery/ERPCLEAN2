package com.erp.modules.costing.domain.dto;

/**
 * Request DTO to update a dimension value's mutable fields (ADR-0025 D-1).
 * All fields nullable — only non-null fields are applied (partial update).
 */
public record UpdateDimensionValueRequest(
        String name,
        String parentUid,      // null means: clear parent (make root); absent means: do not change
        Boolean clearParent    // explicit flag to distinguish "set parent to null" from "do not touch"
) {}
