package com.erp.modules.parties.service;

import com.erp.modules.parties.domain.dto.AssignPartyBranchRequest;
import com.erp.modules.parties.domain.dto.CreateOtherPartyRequest;
import com.erp.modules.parties.domain.dto.OtherPartyDto;
import com.erp.modules.parties.domain.dto.PartyBranchDto;
import com.erp.modules.parties.domain.dto.UpdateOtherPartyRequest;
import com.erp.modules.parties.domain.entity.OtherParty;
import com.erp.modules.parties.domain.entity.OtherPartyBranch;
import com.erp.modules.parties.domain.enums.PartyType;
import com.erp.modules.parties.repository.OtherPartyBranchRepository;
import com.erp.modules.parties.repository.OtherPartyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** OtherParty master administration (FR-PARTY-04, ADR-0006). */
@Service
@Transactional
public class OtherPartyServiceImpl implements OtherPartyService {

    private final OtherPartyRepository otherParties;
    private final OtherPartyBranchRepository otherPartyBranches;
    private final PartyCodeGenerator codeGen;
    private final PartyBranchGuard branchGuard;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public OtherPartyServiceImpl(OtherPartyRepository otherParties,
                                 OtherPartyBranchRepository otherPartyBranches,
                                 PartyCodeGenerator codeGen,
                                 PartyBranchGuard branchGuard,
                                 ScopeGuard scopeGuard,
                                 AuditService audit) {
        this.otherParties = otherParties;
        this.otherPartyBranches = otherPartyBranches;
        this.codeGen = codeGen;
        this.branchGuard = branchGuard;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public OtherPartyDto create(CreateOtherPartyRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());
        validateIdentifiers(req.partyType(), req.tin(), req.vrn(), req.vatRegistered());

        String code = codeGen.next(req.companyId(), "OTHER");
        OtherParty o = new OtherParty(req.companyId(), code, req.partyType(), req.displayName(),
                actorId());
        applyCommon(o, req.partyType(), req.displayName(), req.legalName(), req.tin(),
                req.vatRegistered(), req.vrn(), req.businessRegNo(), req.mobileMoneyNo(),
                req.phone(), req.email(), req.physicalAddress(), req.postalAddress(),
                req.region(), req.district());
        o.setOtherKind(req.otherKind());

        OtherParty saved = otherParties.save(o);
        audit.record(AuditEvent.of(AuditActions.OTHERPARTY_CREATE, "other_parties",
                        saved.getId(), saved.getUid())
                .detail(Map.of("code", saved.getCode(),
                        "partyType", saved.getPartyType().name())));
        return OtherPartyDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public OtherPartyDto getByUid(String uid) {
        // Security fix (finding 2): uid is not authorization — scope-check the loaded entity's company.
        OtherParty o = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), o.getCompanyId());
        return OtherPartyDto.from(o);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<OtherPartyDto> list(Long companyId, String q, Pageable pageable) {
        // Security fix (finding 1): guard before querying — prevents cross-company list via
        // client-supplied companyId. Root is permitted for any company (audited by assertCanActIn).
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        if (q != null && !q.isBlank()) {
            return otherParties.search(companyId, q, pageable).map(OtherPartyDto::from);
        }
        return otherParties.findByCompanyId(companyId, pageable).map(OtherPartyDto::from);
    }

    @Override
    public OtherPartyDto updateByUid(String uid, UpdateOtherPartyRequest req) {
        OtherParty o = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), o.getCompanyId());
        validateIdentifiers(req.partyType(), req.tin(), req.vrn(), req.vatRegistered());

        applyCommon(o, req.partyType(), req.displayName(), req.legalName(), req.tin(),
                req.vatRegistered(), req.vrn(), req.businessRegNo(), req.mobileMoneyNo(),
                req.phone(), req.email(), req.physicalAddress(), req.postalAddress(),
                req.region(), req.district());
        o.setOtherKind(req.otherKind());
        o.setUpdatedAt(Instant.now());
        o.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.OTHERPARTY_UPDATE, "other_parties",
                o.getId(), o.getUid()));
        return OtherPartyDto.from(o);
    }

    @Override
    public void archiveByUid(String uid) {
        OtherParty o = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), o.getCompanyId());
        MasterStatus prev = o.getStatus();
        o.setStatus(MasterStatus.ARCHIVED);
        o.setUpdatedAt(Instant.now());
        o.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.OTHERPARTY_ARCHIVE, "other_parties",
                        o.getId(), o.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ARCHIVED.name())));
    }

    @Override
    public void restoreByUid(String uid) {
        OtherParty o = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), o.getCompanyId());
        MasterStatus prev = o.getStatus();
        o.setStatus(MasterStatus.ACTIVE);
        o.setUpdatedAt(Instant.now());
        o.setUpdatedBy(actorId());
        audit.record(AuditEvent.of(AuditActions.OTHERPARTY_RESTORE, "other_parties",
                        o.getId(), o.getUid())
                .detail(Map.of("previousStatus", prev.name(), "newStatus", MasterStatus.ACTIVE.name())));
    }

    @Override
    public PartyBranchDto assignBranch(String uid, AssignPartyBranchRequest req) {
        OtherParty o = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), o.getCompanyId());
        Long branchId = branchGuard.resolveAndAssertSameCompany(o.getCompanyId(), req.branchUid());

        if (otherPartyBranches.findByOtherPartyIdAndBranchId(o.getId(), branchId).isPresent()) {
            throw new ConflictException("Other party is already associated with that branch.");
        }
        OtherPartyBranch assoc = otherPartyBranches.save(
                new OtherPartyBranch(o, branchId, actorId()));
        audit.record(AuditEvent.of(AuditActions.OTHERPARTY_BRANCH_ADD, "other_parties",
                        o.getId(), o.getUid())
                .detail(Map.of("branchUid", req.branchUid())));
        return PartyBranchDto.of(assoc.getBranchId(), assoc.getAssignedAt(), assoc.getAssignedBy());
    }

    @Override
    public void removeBranch(String uid, String branchUid) {
        OtherParty o = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), o.getCompanyId());
        Long branchId = branchGuard.resolveAndAssertSameCompany(o.getCompanyId(), branchUid);
        otherPartyBranches.deleteByOtherPartyIdAndBranchId(o.getId(), branchId);
        audit.record(AuditEvent.of(AuditActions.OTHERPARTY_BRANCH_REMOVE, "other_parties",
                        o.getId(), o.getUid())
                .detail(Map.of("branchUid", branchUid)));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PartyBranchDto> listBranches(String uid) {
        // Security fix (finding 3): scope-check the party's company before listing its branches.
        OtherParty o = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), o.getCompanyId());
        return otherPartyBranches.findByOtherPartyId(o.getId()).stream()
                .map(a -> PartyBranchDto.of(a.getBranchId(), a.getAssignedAt(), a.getAssignedBy()))
                .toList();
    }

    private OtherParty require(String uid) {
        return Lookups.orNotFound(otherParties.findByUid(uid), "OtherParty", uid);
    }

    private void validateIdentifiers(PartyType partyType, String tin, String vrn,
                                     Boolean vatRegistered) {
        // BR-PARTY-04
        if (partyType == PartyType.BUSINESS && (tin == null || tin.isBlank())) {
            throw new IllegalArgumentException("A business party must have a Tax Identification Number (TIN).");
        }
        // BR-PARTY-06
        if (vrn != null && !vrn.isBlank() && !Boolean.TRUE.equals(vatRegistered)) {
            throw new IllegalArgumentException("A VAT Registration Number (VRN) can only be entered when the party is marked as VAT-registered.");
        }
    }

    private static void applyCommon(OtherParty o, PartyType partyType, String displayName,
                                    String legalName, String tin, Boolean vatRegistered, String vrn,
                                    String businessRegNo, String mobileMoneyNo, String phone,
                                    String email, String physicalAddress, String postalAddress,
                                    String region, String district) {
        o.setPartyType(partyType);
        o.setDisplayName(displayName);
        o.setLegalName(legalName);
        o.setTin(tin);
        o.setVatRegistered(Boolean.TRUE.equals(vatRegistered));
        o.setVrn(vrn);
        o.setBusinessRegNo(businessRegNo);
        o.setMobileMoneyNo(mobileMoneyNo);
        o.setPhone(phone);
        o.setEmail(email);
        o.setPhysicalAddress(physicalAddress);
        o.setPostalAddress(postalAddress);
        o.setRegion(region);
        o.setDistrict(district);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
