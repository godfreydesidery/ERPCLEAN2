package com.erp.modules.parties.repository;

import com.erp.modules.parties.domain.entity.AgentAddress;
import com.erp.modules.parties.domain.enums.AddressRole;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AgentAddressRepository extends JpaRepository<AgentAddress, Long> {

    Optional<AgentAddress> findByUid(String uid);

    List<AgentAddress> findByAgentId(Long agentId);

    List<AgentAddress> findByAgentIdAndAddressRole(Long agentId, AddressRole addressRole);

    Optional<AgentAddress> findByAgentIdAndAddressRoleAndIsDefaultTrue(Long agentId,
                                                                        AddressRole addressRole);
}
