package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.AgentContact;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentContactRepository extends JpaRepository<AgentContact, Long> {

    Optional<AgentContact> findByUid(String uid);

    List<AgentContact> findByAgentId(Long agentId);

    List<AgentContact> findByAgentIdAndStatus(Long agentId, MasterStatus status);

    boolean existsByAgentIdAndIsPrimaryTrue(Long agentId);

    Optional<AgentContact> findByAgentIdAndIsPrimaryTrue(Long agentId);
}
