package com.erp.modules.approvals.service;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.SubmitForApprovalRequest;
import java.util.Optional;

/**
 * The public integration interface every consuming module's service injects (ADR-0022 D-7).
 *
 * <p>This is the ONLY inbound surface a consuming module takes. The consumer imports only
 * {@code approvals.service.ApprovalEngine} and {@code approvals.domain.dto.*} — no engine entity,
 * no repository (D-12). The engine imports no consumer entity, repository, or service.
 *
 * <p>Both methods are scope-checked (assertCanActIn) but NOT separately @perm-gated — the consumer
 * calls submit as a consequence of an act it already gated with its own permission (FR-APR-14, D-7).
 */
public interface ApprovalEngine {

    /**
     * Submit a document for approval. Idempotent per (companyId, documentType, documentUid):
     * a re-submit of an existing (even terminal) request returns the existing request (BR-APR-08).
     *
     * <p>On a policy match: creates a PENDING request with a frozen step snapshot and emits
     * {@code APPROVAL.SUBMITTED}. On no match: creates a terminal APPROVED request
     * ({@code auto_approved = true}, no steps) and emits {@code APPROVAL.RESOLVED} (BR-APR-09,
     * OQ-APR-01 default).
     *
     * <p>Called by the consumer IN ITS OWN transaction (synchronous, same TX as the consumer's
     * state change). The {@code uq_approval_request_document} UNIQUE makes a concurrent double-submit
     * fail the second insert → the service catches and returns the existing request.
     */
    ApprovalRequestDto submitForApproval(SubmitForApprovalRequest req);

    /**
     * The synchronous gate: read a document's approval verdict. Empty = never submitted. Scope-checked.
     * The consumer's finalise transition calls this ("only let the PO go ORDERED if state is APPROVED").
     */
    Optional<ApprovalRequestDto> getApprovalState(String documentType, String documentUid, Long companyId);
}
