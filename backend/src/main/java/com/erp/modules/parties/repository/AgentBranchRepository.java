package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.AgentBranch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentBranchRepository extends JpaRepository<AgentBranch, Long> {

    Optional<AgentBranch> findByAgentIdAndBranchId(Long agentId, Long branchId);

    List<AgentBranch> findByAgentId(Long agentId);

    List<AgentBranch> findByBranchId(Long branchId);

    void deleteByAgentIdAndBranchId(Long agentId, Long branchId);
}
