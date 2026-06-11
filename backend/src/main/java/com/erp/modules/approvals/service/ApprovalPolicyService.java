package com.erp.modules.approvals.service;

import com.erp.modules.approvals.domain.dto.ApprovalPolicyDto;
import com.erp.modules.approvals.domain.dto.CreateApprovalPolicyRequest;
import com.erp.modules.approvals.domain.dto.UpdateApprovalPolicyRequest;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Approval policy master CRUD (FR-APR-01/02, ADR-0022 D-3).
 */
public interface ApprovalPolicyService {

    /** Create a policy + its steps. Validates non-overlap (BR-APR-02) and role codes exist. */
    ApprovalPolicyDto create(CreateApprovalPolicyRequest request);

    /** Edit band/branch/steps/active. Validates non-overlap. In-flight requests unaffected (BR-APR-05). */
    ApprovalPolicyDto update(String uid, UpdateApprovalPolicyRequest request);

    /** Soft-deactivate via MasterStatus (FR-APR-02). */
    ApprovalPolicyDto deactivate(String uid);

    ApprovalPolicyDto getByUid(String uid);

    Page<ApprovalPolicyDto> listByCompany(Long companyId, Pageable pageable);

    Page<ApprovalPolicyDto> listByCompanyAndDocumentType(Long companyId, String documentType, Pageable pageable);
}
