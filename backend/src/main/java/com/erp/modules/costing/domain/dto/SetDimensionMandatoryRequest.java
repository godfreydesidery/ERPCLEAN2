package com.erp.modules.costing.domain.dto;

/**
 * Request DTO to toggle a dimension's mandatory flag (ADR-0025 D-10, FR-CC-13).
 */
public record SetDimensionMandatoryRequest(
        boolean mandatory
) {}
