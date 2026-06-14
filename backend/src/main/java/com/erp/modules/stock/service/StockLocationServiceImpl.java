package com.erp.modules.stock.service;

import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.stock.domain.dto.CreateStockLocationRequest;
import com.erp.modules.stock.domain.dto.StockLocationDto;
import com.erp.modules.stock.domain.dto.UpdateStockLocationRequest;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
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
 * Location master service (ADR-0028 D-4, FR-INVD-01..04).
 */
@Service
public class StockLocationServiceImpl implements StockLocationService {

    private final StockLocationRepository locations;
    private final BranchRepository        branches;
    private final ScopeGuard              scopeGuard;
    private final AuditService            audit;

    public StockLocationServiceImpl(StockLocationRepository locations,
                                     BranchRepository branches,
                                     ScopeGuard scopeGuard,
                                     AuditService audit) {
        this.locations  = locations;
        this.branches   = branches;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
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

        loc.update(request.name(), request.locationType(), principal.userId());
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

        // Clear current default
        locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(
                        principal.companyId(), loc.getBranchId())
                .ifPresent(existing -> {
                    if (!existing.getId().equals(loc.getId())) {
                        existing.clearDefault(principal.userId());
                        locations.save(existing);
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
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal.companyId());
        return locations.findByCompanyIdAndBranchId(
                        principal.companyId(), principal.branchId(), pageable)
                .map(l -> toDto(l));
    }

    @Override
    @Transactional(readOnly = true)
    public List<StockLocationDto> activeForBranch(String branchUid) {
        RequestContext.Principal principal = RequestContext.get();
        var branch = branches.findByUid(branchUid)
                .orElseThrow(() -> NotFoundException.of("Branch", branchUid));
        scopeGuard.assertCanActIn(principal, branch.getCompany().getId());

        return locations.findByCompanyIdAndBranchIdAndStatusOrderByCodeAsc(
                        branch.getCompany().getId(), branch.getId(), MasterStatus.ACTIVE)
                .stream().map(l -> toDto(l)).toList();
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

    private static StockLocationDto toDto(StockLocation l) {
        return new StockLocationDto(
                l.getId(), l.getUid(), l.getCompanyId(), l.getBranchId(),
                l.getCode(), l.getName(), l.getLocationType(), l.isDefault(), l.getStatus());
    }
}
