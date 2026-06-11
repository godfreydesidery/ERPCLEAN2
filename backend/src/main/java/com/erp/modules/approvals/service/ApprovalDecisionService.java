package com.erp.modules.approvals.service;

import com.erp.modules.approvals.domain.dto.ApprovalRequestDto;
import com.erp.modules.approvals.domain.dto.DecideRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Decision actions on approval requests: approve, reject, recall, admin cancel (ADR-0022 D-6).
 */
public interface ApprovalDecisionService {

    /**
     * APPROVE or REJECT the current open step (FR-APR-07/08).
     * Enforces: request must be PENDING; caller must hold the step's role (StepApproverResolver);
     * caller must not be the submitter (SoD — BR-APR-06); optimistic-lock concurrency guard (NFR-APR-05).
     */
    ApprovalRequestDto decide(String requestUid, DecideRequest req);

    /**
     * Submitter (or APPROVALS.ADMIN) recalls a still-PENDING request → RECALLED (FR-APR-10).
     */
    ApprovalRequestDto recall(String requestUid);

    /**
     * APPROVALS.ADMIN cancels a non-terminal request → CANCELLED (FR-APR-15).
     */
    ApprovalRequestDto cancel(String requestUid);

    ApprovalRequestDto getByUid(String uid);

    Page<ApprovalRequestDto> listByCompany(Long companyId, Pageable pageable);

    Page<ApprovalRequestDto> listByCompanyAndStatus(Long companyId, String status, Pageable pageable);

    Page<ApprovalRequestDto> inbox(Pageable pageable);
}
