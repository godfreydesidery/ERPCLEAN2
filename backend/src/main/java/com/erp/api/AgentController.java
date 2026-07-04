package com.erp.api;

import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.UpdateAgentRequest;
import com.erp.modules.parties.service.AgentService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Sales agent master administration (FR-PARTY-03, ADR-0006 D-10/D-11).
 */
@RestController
@RequestMapping("/api/v1/agents")
public class AgentController {

    private final AgentService agents;

    public AgentController(AgentService agents) {
        this.agents = agents;
    }

    @GetMapping
    @PreAuthorize("@perm.has('AGENT.VIEW')")
    public ApiResponse<List<AgentDto>> list(@RequestParam Long companyId,
                                            @RequestParam(required = false) String q,
                                            Pageable pageable) {
        Page<AgentDto> page = agents.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('AGENT.VIEW')")
    public AgentDto get(@PathVariable String uid) {
        return agents.getByUid(uid);
    }

    /**
     * D-5: resolves the ACTIVE INTERNAL agent linked to the current user, so the web can pre-select
     * "my agent" on SO/SI/POS forms (the backend already auto-resolves this on save when no agentUid
     * is passed — this endpoint just lets the UI show the same choice up front). Reuses the same
     * permission as {@link #list} (no new permission code). {@code data} is {@code null} (200 OK)
     * when the caller is root or has no linked internal agent — never a 404, since "no agent" is a
     * perfectly normal outcome here, not an error.
     */
    @GetMapping("/mine")
    @PreAuthorize("@perm.has('AGENT.VIEW')")
    public ApiResponse<AgentDto> mine(@RequestParam String companyUid) {
        Optional<AgentDto> agent = agents.myAgent(companyUid);
        return ApiResponse.ok(agent.orElse(null));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('AGENT.MANAGE')")
    public AgentDto create(@Valid @RequestBody CreateAgentRequest request) {
        return agents.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'agent','AGENT.MANAGE')")
    public AgentDto update(@PathVariable String uid,
                           @Valid @RequestBody UpdateAgentRequest request) {
        return agents.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'agent','AGENT.MANAGE')")
    public void archive(@PathVariable String uid) {
        agents.archiveByUid(uid);
    }

    @PutMapping("/uid/{uid}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'agent','AGENT.MANAGE')")
    public void restore(@PathVariable String uid) {
        agents.restoreByUid(uid);
    }

    @GetMapping("/uid/{uid}/branches")
    @PreAuthorize("@perm.has('AGENT.VIEW')")
    public List<PartyBranchDto> listBranches(@PathVariable String uid) {
        return agents.listBranches(uid);
    }

    @PostMapping("/uid/{uid}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'agent','PARTY.BRANCH.ASSIGN')")
    public PartyBranchDto assignBranch(@PathVariable String uid,
                                       @Valid @RequestBody AssignPartyBranchRequest request) {
        return agents.assignBranch(uid, request);
    }

    @DeleteMapping("/uid/{uid}/branches/{branchUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'agent','PARTY.BRANCH.ASSIGN')")
    public void removeBranch(@PathVariable String uid, @PathVariable String branchUid) {
        agents.removeBranch(uid, branchUid);
    }
}
