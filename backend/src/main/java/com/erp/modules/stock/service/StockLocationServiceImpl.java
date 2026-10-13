package com.erp.modules.stock.service;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.parties.domain.dto.AgentDto;
import com.erp.modules.parties.service.AgentService;
import com.erp.modules.stock.domain.dto.CreateStockLocationRequest;
import com.erp.modules.stock.domain.dto.StockLocationDto;
import com.erp.modules.stock.domain.dto.UpdateStockLocationRequest;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Location master service (ADR-0028 D-4, FR-INVD-01..04; van/agent link ADR-0051 D-8.4).
 */
@Service
public class StockLocationServiceImpl implements StockLocationService {

    private final StockLocationRepository locations;
    private final BranchRepository        branches;
    private final UserBranchRepository    userBranches;
    private final AgentService            agentService;
    private final ScopeGuard              scopeGuard;
    private final AuditService            audit;
    private final LocationResolver        locationResolver;

    public StockLocationServiceImpl(StockLocationRepository locations,
                                     BranchRepository branches,
                                     UserBranchRepository userBranches,
                                     AgentService agentService,
                                     ScopeGuard scopeGuard,
                                     AuditService audit,
                                     LocationResolver locationResolver) {
        this.locations        = locations;
        this.branches         = branches;
        this.userBranches     = userBranches;
        this.agentService     = agentService;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
        this.locationResolver = locationResolver;
    }

    @Override
    @Transactional
    public StockLocationDto create(CreateStockLocationRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal.companyId());

        // Resolve the owning branch — must belong to this company
        var branch = branches.findByUid(request.branchUid())
                .orElseThrow(() -> NotFoundException.of("Branch", request.branchUid()));
        if (!branch.getCompany().getId().equals(principal.companyId())) {
            throw com.erp.platform.common.api.ForbiddenException.notPermitted();
        }

        // Pre-check the real unique key (uq_stock_location_company_code: company_id, code — the
        // code is unique company-wide, not just within the branch) so the caller gets a specific,
        // friendly 409 instead of the generic DB-constraint catch-all (error-message-hygiene
        // defect D2).
        if (locations.existsByCompanyIdAndCode(principal.companyId(), request.code())) {
            throw new ConflictException(
                    "A stock location with code " + request.code() + " already exists in this company.");
        }

        // STK-07: the TRANSIT- code prefix is how LocationResolver recognises the branch's in-transit
        // location. A second one would be ambiguous, and a default or non-Other one makes no sense,
        // so the prefix is only accepted to (re)create a missing in-transit location by hand.
        boolean transitCode = LocationResolver.hasTransitCode(request.code());
        if (transitCode) {
            boolean branchHasTransit = locations
                    .findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                            principal.companyId(), branch.getId(), MasterStatus.ACTIVE)
                    .stream()
                    .anyMatch(l -> !l.isDefault() && LocationResolver.hasTransitCode(l.getCode()));
            if (branchHasTransit || request.makeDefault()
                    || request.locationType() != LocationType.OTHER) {
                throw new IllegalArgumentException(
                        "Codes starting with " + LocationResolver.TRANSIT_CODE_PREFIX
                      + " are reserved for the branch's in-transit location. Choose a different code.");
            }
        }

        // If makeDefault, clear any existing default first and FLUSH immediately.
        // Without the flush the Hibernate batch could INSERT the new is_default=true row
        // before the UPDATE clearing the old one reaches the DB, violating the partial
        // unique index uq_stock_location_one_default (company_id, branch_id) WHERE is_default.
        if (request.makeDefault()) {
            locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(
                    principal.companyId(), branch.getId())
                    .ifPresent(existing -> {
                        existing.clearDefault(principal.userId());
                        locations.saveAndFlush(existing);
                    });
        }

        StockLocation loc = new StockLocation(
                principal.companyId(), branch.getId(),
                request.code(), request.name(), request.locationType(),
                request.makeDefault(), principal.userId());
        applyAgentAssignment(loc, request.agentUid());
        if (transitCode) {
            // LBO-29: goods on the road can be neither sold nor picked.
            loc.setSellable(false);
            loc.setPickable(false);
        }
        locations.save(loc);

        audit.record(AuditEvent.of(AuditActions.STOCK_LOCATION_CREATE, "stock_locations",
                        loc.getId(), loc.getUid())
                .detail(Map.of("code", loc.getCode(), "name", loc.getName(),
                        "branchId", String.valueOf(branch.getId()))));

        return toDto(loc);
    }

    @Override
    @Transactional
    public StockLocationDto update(String locationUid, UpdateStockLocationRequest request) {
        RequestContext.Principal principal = RequestContext.get();
        StockLocation loc = findAndAssertScope(locationUid, principal);

        // STK-07: the in-transit location is system-managed. Renaming it is harmless; retyping it
        // (or giving it a van agent) is not — every dispatched transfer holds its goods there.
        if (locationResolver.isInTransitLocation(loc)
                && (request.locationType() != loc.getLocationType()
                    || (request.agentUid() != null && !request.agentUid().isBlank()))) {
            throw new IllegalArgumentException(
                    "The in-transit location is managed by the system. You can rename it, but not "
                  + "change its type.");
        }

        loc.update(request.name(), request.locationType(), principal.userId());
        applyAgentAssignment(loc, request.agentUid());
        locations.save(loc);

        audit.record(AuditEvent.of(AuditActions.STOCK_LOCATION_UPDATE, "stock_locations",
                        loc.getId(), loc.getUid())
                .detail(Map.of("name", request.name(), "locationType", request.locationType().name())));
        return toDto(loc);
    }

    @Override
    @Transactional
    public void deactivate(String locationUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockLocation loc = findAndAssertScope(locationUid, principal);

        if (loc.isDefault()) {
            throw new IllegalStateException(
                    "Cannot deactivate the branch default location. Set a different default first.");
        }
        // STK-07: deactivating it would strand every dispatched transfer ("No in-transit location
        // is configured…" on Receive).
        if (locationResolver.isInTransitLocation(loc)) {
            throw new IllegalStateException(
                    "The in-transit location is managed by the system and cannot be deactivated.");
        }
        // STK-20: an inactive location can't be counted or transferred out of, yet its stock still
        // counts in the branch totals — so stock left there would be stranded. (Scope was asserted
        // on the loaded location above; the probe is keyed by its id.)
        if (locations.hasNonZeroOnHand(loc.getId())) {
            throw new IllegalStateException(
                    "This location still holds stock. Transfer or adjust it to zero before "
                  + "deactivating the location.");
        }
        loc.deactivate(principal.userId());
        locations.save(loc);

        audit.record(AuditEvent.of(AuditActions.STOCK_LOCATION_DEACTIVATE, "stock_locations",
                loc.getId(), loc.getUid()).detail(Map.of()));
    }

    @Override
    @Transactional
    public void reactivate(String locationUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockLocation loc = findAndAssertScope(locationUid, principal);
        loc.reactivate(principal.userId());
        locations.save(loc);

        audit.record(AuditEvent.of(AuditActions.STOCK_LOCATION_UPDATE, "stock_locations",
                loc.getId(), loc.getUid()).detail(Map.of("action", "reactivate")));
    }

    @Override
    @Transactional
    public StockLocationDto setDefault(String locationUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockLocation loc = findAndAssertScope(locationUid, principal);

        // STK-07: sales and receipts would then post into the in-transit holding location.
        if (locationResolver.isInTransitLocation(loc)) {
            throw new IllegalStateException(
                    "The in-transit location cannot be the branch default. Choose another location.");
        }

        // Clear the current default and flush immediately so the UPDATE reaches Postgres before
        // the UPDATE that sets the new default row — prevents a momentary two-row violation on
        // the partial unique index uq_stock_location_one_default (STOCK-011).
        locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(
                        principal.companyId(), loc.getBranchId())
                .ifPresent(existing -> {
                    if (!existing.getId().equals(loc.getId())) {
                        existing.clearDefault(principal.userId());
                        locations.saveAndFlush(existing);
                    }
                });

        loc.markDefault(principal.userId());
        locations.save(loc);

        audit.record(AuditEvent.of(AuditActions.STOCK_LOCATION_SET_DEFAULT, "stock_locations",
                loc.getId(), loc.getUid()).detail(Map.of()));
        return toDto(loc);
    }

    @Override
    @Transactional(readOnly = true)
    public StockLocationDto getByUid(String locationUid) {
        RequestContext.Principal principal = RequestContext.get();
        StockLocation loc = findAndAssertScope(locationUid, principal);
        return toDto(loc);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockLocationDto> listForBranch(Pageable pageable) {
        return listForBranch(null, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<StockLocationDto> listForBranch(String branchUid, Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal.companyId());

        // No explicit branch → keep the historic behaviour: the caller's active branch.
        Long branchId = (branchUid == null || branchUid.isBlank())
                ? principal.branchId()
                : resolveAccessibleBranchId(branchUid, principal);

        return locations.findByCompanyIdAndBranchId(
                        principal.companyId(), branchId, pageable)
                .map(l -> toDto(l));
    }

    /**
     * Resolve a branch uid the caller is allowed to list locations for. Mirrors the X-Branch-Uid
     * override validation in {@code JwtRequestContextFilter} (ADR-0003): the branch must belong to
     * the caller's company, and a non-root caller must have an active {@code user_branch} assignment
     * to it. Any defect fails closed (403).
     */
    private Long resolveAccessibleBranchId(String branchUid, RequestContext.Principal principal) {
        Branch branch = branches.findByUid(branchUid)
                .orElseThrow(() -> NotFoundException.of("Branch", branchUid));

        // Same-company is the floor for everyone (root may act cross-company per ScopeGuard).
        scopeGuard.assertCanActIn(principal, branch.getCompany().getId());

        // Non-root callers may only list a branch they are actively assigned to — same rule the
        // request-scope branch override enforces.
        // Revocation-aware since P3-14 (ADR-0062). This used to call findByUserIdAndBranchId, which
        // filters on neither revokedAt nor active - so revoking someone's branch left them still able
        // to list its locations. The header path was narrowed at the same time, so the two now agree.
        if (!principal.root()
                && userBranches.findByUserIdAndBranchIdAndRevokedAtIsNullAndActiveTrue(
                        principal.userId(), branch.getId()).isEmpty()) {
            throw com.erp.platform.common.api.ForbiddenException.notPermitted();
        }
        return branch.getId();
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockLocationDto> activeForBranch(String branchUid) {
        RequestContext.Principal principal = RequestContext.get();
        var branch = branches.findByUid(branchUid)
                .orElseThrow(() -> NotFoundException.of("Branch", branchUid));
        scopeGuard.assertCanActIn(principal, branch.getCompany().getId());

        // STK-23: this list feeds pickers (stock count, van reconciliation). The in-transit location
        // is filled and emptied only by transfer dispatch/receive — counting it, or choosing it by
        // hand, corrupts the transit balance — so it is not offered.
        List<StockLocation> active = locations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                branch.getCompany().getId(), branch.getId(), MasterStatus.ACTIVE);
        Long transitId = LocationResolver.pickInTransit(active).map(StockLocation::getId).orElse(null);
        return active.stream()
                .filter(l -> !l.getId().equals(transitId))
                .map(l -> toDto(l)).toList();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private StockLocation findAndAssertScope(String uid, RequestContext.Principal principal) {
        StockLocation loc = locations.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("StockLocation", uid));
        scopeGuard.assertCanActIn(principal, loc.getCompanyId());
        return loc;
    }

    /**
     * Applies (or clears) the van/agent link (ADR-0051 D-8.4). Only a {@code VAN}-type location may
     * carry an agent — a non-VAN location silently keeps {@code agentId == null} (auto-cleared on a
     * type change away from VAN) and rejects an explicit assignment attempt. {@code null} means
     * "leave the current assignment unchanged"; blank ({@code ""}) explicitly clears it.
     */
    private void applyAgentAssignment(StockLocation loc, String agentUidRaw) {
        if (loc.getLocationType() != LocationType.VAN) {
            if (agentUidRaw != null && !agentUidRaw.isBlank()) {
                throw new IllegalArgumentException(
                        "An agent can only be assigned to a VAN location.");
            }
            loc.setAgentId(null);
            return;
        }
        if (agentUidRaw == null) {
            return; // VAN, no change requested — leave the existing assignment (or none) as-is
        }
        if (agentUidRaw.isBlank()) {
            loc.setAgentId(null);
            return;
        }
        AgentDto agent = agentService.getByUid(agentUidRaw);
        if (!agent.companyId().equals(loc.getCompanyId())) {
            throw new IllegalArgumentException(
                    "The selected agent does not belong to your company.");
        }
        Long excludeId = loc.getId() != null ? loc.getId() : -1L;
        if (locations.existsByAgentIdAndStatusAndIdNot(agent.id(), MasterStatus.ACTIVE, excludeId)) {
            throw new ConflictException("This agent already runs another active van.");
        }
        loc.setAgentId(agent.id());
    }

    private StockLocationDto toDto(StockLocation l) {
        String agentUid = null;
        String agentName = null;
        if (l.getAgentId() != null) {
            AgentDto agent = agentService.getByCompanyIdAndId(l.getCompanyId(), l.getAgentId());
            if (agent != null) {
                agentUid  = agent.uid();
                agentName = agent.displayName();
            }
        }
        return new StockLocationDto(
                l.getId(), l.getUid(), l.getCompanyId(), l.getBranchId(),
                l.getCode(), l.getName(), l.getLocationType(), l.isDefault(),
                l.getParentLocationId(), l.isAllowNegative(), l.isPickable(), l.isSellable(),
                l.getGlAccountId(), l.getStatus(), agentUid, agentName);
    }
}
