package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.UpdateAgentRequest;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AgentService {

    AgentDto create(CreateAgentRequest request);

    AgentDto getByUid(String uid);

    Page<AgentDto> list(Long companyId, String q, Pageable pageable);

    AgentDto updateByUid(String uid, UpdateAgentRequest request);

    void archiveByUid(String uid);

    void restoreByUid(String uid);

    PartyBranchDto assignBranch(String uid, AssignPartyBranchRequest request);

    void removeBranch(String uid, String branchUid);

    List<PartyBranchDto> listBranches(String uid);
}
