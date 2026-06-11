package com.erp.modules.approvals.repository;

import com.erp.modules.approvals.domain.entity.ApprovalPolicyStep;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ApprovalPolicyStepRepository extends JpaRepository<ApprovalPolicyStep, Long> {

    Optional<ApprovalPolicyStep> findByUid(String uid);

    List<ApprovalPolicyStep> findByApprovalPolicyIdOrderBySequence(Long approvalPolicyId);

    void deleteByApprovalPolicyId(Long approvalPolicyId);
}
