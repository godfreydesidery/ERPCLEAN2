package com.erp.modules.crm.domain.enums;

/**
 * Classifies a pipeline stage against the orthogonal opportunity "status" axis (P3).
 * Reconciles with {@link OpportunityStatus}: a stage typed WON/LOST is a terminal funnel
 * position, an OPEN stage is an in-progress position. NULL = unclassified (default).
 */
public enum PipelineStageType {
    OPEN,
    WON,
    LOST
}
