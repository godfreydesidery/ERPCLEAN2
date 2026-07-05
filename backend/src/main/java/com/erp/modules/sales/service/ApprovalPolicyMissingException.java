package com.erp.modules.sales.service;

/**
 * Internal control-flow signal thrown by {@link SalesApprovalGate#submitIndependent} when the
 * approvals engine had no matching {@code SALES_ORDER} policy for the submitted order and
 * therefore auto-approved it by default (BR-APR-09-style fallback) — for an over-threshold order
 * that is a misconfiguration, not a real approval.
 *
 * <p>Persona UAT follow-up R1: {@code submitIndependent} runs in its OWN {@code REQUIRES_NEW}
 * transaction (persona UAT I3), so throwing this unchecked exception from inside that method rolls
 * back ONLY that independent transaction — the auto-approved {@code ApprovalRequest} the engine
 * just wrote is never persisted. Without this, the misconfiguration block left a durable, terminal
 * {@code APPROVED} request behind that poisoned every future confirm attempt for that order — even
 * after an administrator added a matching policy, because the stale request was already terminal
 * and {@code requiresApproval} only reads {@code SalesSettings}, never the engine's policies.
 *
 * <p>Never surfaced outside {@link SalesOrderServiceImpl}: it is caught immediately by {@code
 * autoSubmitForApprovalIfOverThreshold} and converted into the friendly, user-facing block message.
 * Package-private — purely an implementation detail shared between the two classes.
 */
class ApprovalPolicyMissingException extends RuntimeException {

    ApprovalPolicyMissingException() {
        super("No matching approval policy for this document type.");
    }
}
