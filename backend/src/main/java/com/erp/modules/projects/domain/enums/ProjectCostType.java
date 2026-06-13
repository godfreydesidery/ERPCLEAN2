package com.erp.modules.projects.domain.enums;

/**
 * Cost-type classification for project expense lines (ADR-0033 D-6, OQ-PROJ-02, BR-PROJ-05).
 *
 * <p>Derivation mapping (D-6):
 * <ul>
 *   <li>COGS / ISSUE_TO_PROJECT / stock-receipt GL source → MATERIAL</li>
 *   <li>AP bill goods line (GRNI/Purchases/Inventory account) → MATERIAL</li>
 *   <li>AP bill service line (non-inventory account) → SUBCONTRACT</li>
 *   <li>Payroll source type (future) → LABOUR</li>
 *   <li>Manual journal with explicit project_cost_type → that type</li>
 *   <li>Any other → OTHER</li>
 * </ul>
 */
public enum ProjectCostType {

    /** Raw materials, purchased stock, COGS-type costs. */
    MATERIAL,

    /** Subcontracted labour or services — AP service legs. */
    SUBCONTRACT,

    /** Payroll / timesheet labour cost (future; tagged via manual journal in v1). */
    LABOUR,

    /** Overhead allocations: rent, utilities, indirect costs. */
    OVERHEAD,

    /** Catch-all for unclassified expense lines. */
    OTHER;
}
