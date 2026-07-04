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

    /**
     * Internal lookup by (companyId, numeric id) — for cross-module callers (e.g. stock-location
     * van assignment, van-reconciliation) that hold an agentId and need uid/displayName for
     * enrichment. Company-scoped (TenantScopingRulesTest): the caller's already-established company
     * scope filters the lookup in the database, so a foreign company's agent row is never loaded.
     * Returns {@code null} when no agent exists for that id in that company.
     */
    AgentDto getByCompanyIdAndId(Long companyId, Long id);

    Page<AgentDto> list(Long companyId, String q, Pageable pageable);

    AgentDto updateByUid(String uid, UpdateAgentRequest request);

    void archiveByUid(String uid);

    void restoreByUid(String uid);

    PartyBranchDto assignBranch(String uid, AssignPartyBranchRequest request);

    void removeBranch(String uid, String branchUid);

    List<PartyBranchDto> listBranches(String uid);
}
