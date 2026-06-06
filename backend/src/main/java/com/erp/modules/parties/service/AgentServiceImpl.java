package com.erp.modules.parties.service;

import com.erp.modules.iam.service.UserLookupService;
import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateAgentRequest;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.UpdateAgentRequest;
import com.erp.modules.parties.domain.entity.Agent;
import com.erp.modules.parties.domain.entity.AgentBranch;
import com.erp.modules.parties.domain.enums.AgentKind;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.AgentBranchRepository;
import com.erp.modules.parties.repository.AgentRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Agent master administration (FR-PARTY-03, ADR-0006 D-5).
 *
 * <p>Cross-module boundary: BR-PARTY-10 (internal agent must reference an ACTIVE user) is
 * validated by calling {@link UserLookupService} — the narrow IAM surface exposed for this purpose
 * (ADR-0006 D-1/D-5). No IAM repository or entity is imported directly.
 */
@Service
@Transactional
public class AgentServiceImpl implements AgentService {

    private final AgentRepository agents;
    private final AgentBranchRepository agentBranches;
    private final PartyCodeGenerator codeGen;
    private final PartyBranchGuard branchGuard;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final UserLookupService userLookup;

    public AgentServiceImpl(AgentRepository agents,
                            AgentBranchRepository agentBranches,
                            PartyCodeGenerator codeGen,
                            PartyBranchGuard branchGuard,
                            ScopeGuard scopeGuard,
                            AuditService audit,
                            UserLookupService userLookup) {
        this.agents = agents;
        this.agentBranches = agentBranches;
        this.codeGen = codeGen;
        this.branchGuard = branchGuard;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
        this.userLookup = userLookup;
    }

    @Override
    public AgentDto create(CreateAgentRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());
        validateIdentifiers(req.partyType(), req.tin(), req.vrn(), req.vatRegistered());
        validateAgentKind(req.agentKind(), req.appUserId(), req.companyId());

        String code = codeGen.next(req.companyId(), "AGENT");
        Agent a = new Agent(req.companyId(), code, req.partyType(), req.displayName(),
                req.agentKind(), actorId());
        applyCommon(a, req.partyType(), req.displayName(), req.legalName(), req.tin(),
                req.vatRegistered(), req.vrn(), req.businessRegNo(), req.mobileMoneyNo(),
                req.phone(), req.email(), req.physicalAddress(), req.postalAddress(),
                req.region(), req.district());
        a.setAgentKind(req.agentKind());
        a.setAppUserId(req.agentKind() == AgentKind.INTERNAL ? req.appUserId() : null);

        Agent saved = agents.save(a);
        Map<String, Object> detail = new HashMap<>();
        detail.put("code", saved.getCode());
        detail.put("partyType", saved.getPartyType().name());
        detail.put("agentKind", saved.getAgentKind().name());
        if (saved.getAppUserId() != null) {
            detail.put("appUserId", String.valueOf(saved.getAppUserId()));
        }
        audit.record(AuditEvent.of(AuditActions.AGENT_CREATE, "agents",
                saved.getId(), saved.getUid()).detail(detail));
        return AgentDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public AgentDto getByUid(String uid) {
        // Security fix (finding 2): uid is not authorization — scope-check the loaded entity's company.
        Agent a = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), a.getCompanyId());
        return AgentDto.from(a);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AgentDto> list(Long companyId, String q, Pageable pageable) {
        // Security fix (finding 1): guard before querying — prevents cross-company list via
        // client-supplied companyId. Root is permitted for any company (audited by assertCanActIn).
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return agents.search(companyId, q, pageable).map(AgentDto::from);
        }
        return agents.findByCompanyId(companyId, pageable).map(AgentDto::from);
    }

    @Override
    public AgentDto updateByUid(String uid, UpdateAgentRequest req) {
        Agent a = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), a.getCompanyId());
        validateIdentifiers(req.partyType(), req.tin(), req.vrn(), req.vatRegistered());
        validateAgentKind(req.agentKind(), req.appUserId(), a.getCompanyId());

        applyCommon(a, req.partyType(), req.displayName(), req.legalName(), req.tin(),
                req.vatRegistered(), req.vrn(), req.businessRegNo(), req.mobileMoneyNo(),
                req.phone(), req.email(), req.physicalAddress(), req.postalAddress(),
                req.region(), req.district());
        a.setAgentKind(req.agentKind());
        a.setAppUserId(req.agentKind() == AgentKind.INTERNAL ? req.appUserId() : null);
        a.setUpdatedAt(Instant.now());
        a.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.AGENT_UPDATE, "agents", a.getId(), a.getUid()));
        return AgentDto.from(a);
    }

    @Override
    public void archiveByUid(String uid) {
        Agent a = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), a.getCompanyId());
        MasterStatus prev = a.getStatus();
        a.setStatus(MasterStatus.ARCHIVED);
        a.setUpdatedAt(Instant.now());
        a.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.AGENT_ARCHIVE, "agents", a.getId(), a.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ARCHIVED.name())));
    }

    @Override
    public void restoreByUid(String uid) {
        Agent a = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), a.getCompanyId());
        MasterStatus prev = a.getStatus();
        a.setStatus(MasterStatus.ACTIVE);
        a.setUpdatedAt(Instant.now());
        a.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.AGENT_RESTORE, "agents", a.getId(), a.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ACTIVE.name())));
    }

    @Override
    public PartyBranchDto assignBranch(String uid, AssignPartyBranchRequest req) {
        Agent a = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), a.getCompanyId());
        Long branchId = branchGuard.resolveAndAssertSameCompany(a.getCompanyId(), req.branchUid());

        if (agentBranches.findByAgentIdAndBranchId(a.getId(), branchId).isPresent()) {
            throw new ConflictException("Agent is already associated with that branch.");
        }
        AgentBranch assoc = agentBranches.save(new AgentBranch(a, branchId, actorId()));
        audit.record(AuditEvent.of(AuditActions.AGENT_BRANCH_ADD, "agents", a.getId(), a.getUid())
                .detail(Map.of("branchUid", req.branchUid())));
        return PartyBranchDto.of(assoc.getBranchId(), assoc.getAssignedAt(), assoc.getAssignedBy());
    }

    @Override
    public void removeBranch(String uid, String branchUid) {
        Agent a = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), a.getCompanyId());
        Long branchId = branchGuard.resolveAndAssertSameCompany(a.getCompanyId(), branchUid);
        agentBranches.deleteByAgentIdAndBranchId(a.getId(), branchId);
        audit.record(AuditEvent.of(AuditActions.AGENT_BRANCH_REMOVE, "agents", a.getId(), a.getUid())
                .detail(Map.of("branchUid", branchUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyBranchDto> listBranches(String uid) {
        // Security fix (finding 3): scope-check the party's company before listing its branches.
        Agent a = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), a.getCompanyId());
        return agentBranches.findByAgentId(a.getId()).stream()
                .map(x -> PartyBranchDto.of(x.getBranchId(), x.getAssignedAt(), x.getAssignedBy()))
                .toList();
    }

    private Agent require(String uid) {
        return Lookups.orNotFound(agents.findByUid(uid), "Agent", uid);
    }

    private void validateIdentifiers(PartyType partyType, String tin, String vrn,
                                     Boolean vatRegistered) {
        if (partyType == PartyType.BUSINESS && (tin == null || tin.isBlank())) {
            throw new IllegalArgumentException("A business agent must have a TIN (BR-PARTY-04).");
        }
        if (vrn != null && !vrn.isBlank() && !Boolean.TRUE.equals(vatRegistered)) {
            throw new IllegalArgumentException("VRN may only be set when the agent is VAT-registered (BR-PARTY-06).");
        }
    }

    /**
     * BR-PARTY-10/11: INTERNAL must reference an ACTIVE user in the agent's company; EXTERNAL must
     * have no user.
     *
     * <p>Security fix (finding 4): company-membership signal uses branch assignments — an internal
     * agent must be staff of the agent's company (has at least one user_branch in that company).
     * This scopes the existing id-based check without a contract change. A stronger fix (switching
     * the API to user-uid with an explicit company assertion) is noted as a follow-up.
     */
    private void validateAgentKind(AgentKind kind, Long appUserId, Long companyId) {
        if (kind == AgentKind.INTERNAL) {
            if (appUserId == null) {
                throw new IllegalArgumentException(
                        "An internal agent must reference an app user (BR-PARTY-10).");
            }
            if (!userLookup.isActiveUserInCompany(appUserId, companyId)) {
                throw new IllegalArgumentException(
                        "The referenced app user must be ACTIVE and belong to the agent's company (BR-PARTY-10).");
            }
        } else {
            if (appUserId != null) {
                throw new IllegalArgumentException(
                        "An external agent must not reference an app user (BR-PARTY-11).");
            }
        }
    }

    private static void applyCommon(Agent a, PartyType partyType, String displayName,
                                    String legalName, String tin, Boolean vatRegistered, String vrn,
                                    String businessRegNo, String mobileMoneyNo, String phone,
                                    String email, String physicalAddress, String postalAddress,
                                    String region, String district) {
        a.setPartyType(partyType);
        a.setDisplayName(displayName);
        a.setLegalName(legalName);
        a.setTin(tin);
        a.setVatRegistered(Boolean.TRUE.equals(vatRegistered));
        a.setVrn(vrn);
        a.setBusinessRegNo(businessRegNo);
        a.setMobileMoneyNo(mobileMoneyNo);
        a.setPhone(phone);
        a.setEmail(email);
        a.setPhysicalAddress(physicalAddress);
        a.setPostalAddress(postalAddress);
        a.setRegion(region);
        a.setDistrict(district);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
