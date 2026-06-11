package com.erp.modules.approvals.repository;

import com.erp.modules.approvals.domain.entity.ApprovalDecision;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalDecisionRepository extends JpaRepository<ApprovalDecision, Long> {

    Optional<ApprovalDecision> findByUid(String uid);

    List<ApprovalDecision> findByApprovalRequestIdOrderByDecidedAt(Long approvalRequestId);

    List<ApprovalDecision> findByApprovalRequestStepIdOrderByDecidedAt(Long approvalRequestStepId);
}
