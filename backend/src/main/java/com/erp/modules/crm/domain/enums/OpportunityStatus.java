package com.erp.modules.crm.domain.enums;

/**
 * Opportunity lifecycle — the "status" axis (ADR-0031 D-2).
 * Orthogonal to the pipeline stage (which is the "position in the funnel" axis).
 * CONVERTED is NOT a status value — it is recorded via converted_document_uid.
 */
public enum OpportunityStatus {
    OPEN,
    WON,
    LOST
}
