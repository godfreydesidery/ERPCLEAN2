package com.erp.modules.approvals.repository;

import com.erp.modules.approvals.domain.entity.ApprovalRequest;
import com.erp.modules.approvals.domain.enums.ApprovalRequestStatus;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ApprovalRequestRepository extends JpaRepository<ApprovalRequest, Long> {

    Optional<ApprovalRequest> findByUid(String uid);

    /** ScopeGuard resolver (ADR-0022 D-11). */
    @Query("SELECT r.companyId FROM ApprovalRequest r WHERE r.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /** Idempotency lookup — the synchronous gate and re-submit detection (BR-APR-08, D-7). */
    Optional<ApprovalRequest> findByCompanyIdAndDocumentTypeAndDocumentUid(
            Long companyId, String documentType, String documentUid);

    Page<ApprovalRequest> findByCompanyId(Long companyId, Pageable pageable);

    Page<ApprovalRequest> findByCompanyIdAndStatus(Long companyId, ApprovalRequestStatus status, Pageable pageable);

    Page<ApprovalRequest> findByCompanyIdAndDocumentType(Long companyId, String documentType, Pageable pageable);

    Page<ApprovalRequest> findByCompanyIdAndSubmittedBy(Long companyId, Long submittedBy, Pageable pageable);
}
