package com.erp.modules.stock.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.UserBranch;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.iam.repository.UserBranchRepository;
import com.erp.modules.stock.domain.dto.CreateStockLocationRequest;
import com.erp.modules.stock.domain.dto.StockLocationDto;
import com.erp.modules.stock.domain.entity.StockLocation;
import com.erp.modules.stock.domain.enums.LocationType;
import com.erp.modules.stock.repository.StockLocationRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

/**
 * Unit tests for {@link StockLocationServiceImpl}.
 *
 * <p>Bug #12 regression: creating a stock location with {@code makeDefault=true} when another
 * location is already the branch default violated the partial unique index
 * {@code uq_stock_location_one_default (company_id, branch_id) WHERE is_default=true}.
 *
 * <p>Root cause: the old code called {@code locations.save(existing)} (clearing the old default)
 * followed by {@code locations.save(loc)} (inserting new default).  Hibernate could flush the
 * INSERT before the UPDATE, leaving two {@code is_default=true} rows momentarily and triggering
 * the unique-constraint violation.
 *
 * <p>Fix: use {@code locations.saveAndFlush(existing)} so the UPDATE reaches the DB before
 * the new entity is INSERTed.
 */
class StockLocationServiceImplTest {

    private StockLocationRepository locations;
    private BranchRepository        branches;
    private UserBranchRepository    userBranches;
    private ScopeGuard               scopeGuard;
    private AuditService             audit;

    private StockLocationServiceImpl service;

    private static final Long COMPANY_ID = 10L;
    private static final Long BRANCH_ID  = 20L;
    private static final Long USER_ID    = 1L;

    @BeforeEach
    void setUp() {
        locations    = mock(StockLocationRepository.class);
        branches     = mock(BranchRepository.class);
        userBranches = mock(UserBranchRepository.class);
        scopeGuard   = mock(ScopeGuard.class);
        audit        = mock(AuditService.class);

        service = new StockLocationServiceImpl(locations, branches, userBranches, scopeGuard, audit);

        RequestContext.set(new RequestContext.Principal(
                USER_ID, "user@test.com", false, COMPANY_ID, BRANCH_ID, null));

        // Branch stub
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);

        Branch branch = mock(Branch.class);
        when(branch.getId()).thenReturn(BRANCH_ID);
        when(branch.getCompany()).thenReturn(company);
        when(branches.findByUid("BRANCH-UID")).thenReturn(Optional.of(branch));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Bug #12: when makeDefault=true and an existing default is present,
    // saveAndFlush must be called on the old default BEFORE the new location
    // is saved — otherwise Hibernate may INSERT before UPDATE.
    // -------------------------------------------------------------------------

    @Test
    void create_makeDefault_withExistingDefault_saveAndFlushesOldDefaultFirst() {
        // Existing default location
        StockLocation oldDefault = mock(StockLocation.class);
        when(oldDefault.getId()).thenReturn(99L);
        when(locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(COMPANY_ID, BRANCH_ID))
                .thenReturn(Optional.of(oldDefault));

        // New location returned by save
        StockLocation newLoc = stubNewLocation(true);
        when(locations.save(any(StockLocation.class))).thenReturn(newLoc);
        when(locations.saveAndFlush(oldDefault)).thenReturn(oldDefault);

        CreateStockLocationRequest req = new CreateStockLocationRequest(
                "LOC-001", "Main Warehouse", LocationType.WAREHOUSE, "BRANCH-UID", true);

        service.create(req);

        // saveAndFlush on the OLD default must be called BEFORE save on the new location
        InOrder order = inOrder(locations);
        order.verify(locations).saveAndFlush(oldDefault);
        order.verify(locations).save(any(StockLocation.class));
    }

    @Test
    void create_makeDefault_noExistingDefault_doesNotCallSaveAndFlush() {
        // No existing default
        when(locations.findByCompanyIdAndBranchIdAndIsDefaultTrue(COMPANY_ID, BRANCH_ID))
                .thenReturn(Optional.empty());

        StockLocation newLoc = stubNewLocation(true);
        when(locations.save(any(StockLocation.class))).thenReturn(newLoc);

        CreateStockLocationRequest req = new CreateStockLocationRequest(
                "LOC-002", "Secondary Store", LocationType.STORE, "BRANCH-UID", true);

        service.create(req);

        // No old default to flush
        verify(locations, never()).saveAndFlush(any());
        verify(locations).save(any(StockLocation.class));
    }

    @Test
    void create_notMakeDefault_doesNotTouchExistingDefault() {
        StockLocation newLoc = stubNewLocation(false);
        when(locations.save(any(StockLocation.class))).thenReturn(newLoc);

        CreateStockLocationRequest req = new CreateStockLocationRequest(
                "LOC-003", "Quarantine Area", LocationType.QUARANTINE, "BRANCH-UID", false);

        service.create(req);

        // findByCompanyIdAndBranchIdAndIsDefaultTrue must NOT be called when makeDefault=false
        verify(locations, never()).findByCompanyIdAndBranchIdAndIsDefaultTrue(any(), any());
        verify(locations, never()).saveAndFlush(any());
    }

    // -------------------------------------------------------------------------
    // Branch-filtered list: an explicit branchUid lists that branch (not just the
    // caller's active branch) so a location created for any branch is findable.
    // -------------------------------------------------------------------------

    private static final Long OTHER_BRANCH_ID = 30L;
    private static final Pageable PAGEABLE     = PageRequest.of(0, 20);

    @Test
    void listForBranch_noBranchUid_usesActiveBranch() {
        when(locations.findByCompanyIdAndBranchId(COMPANY_ID, BRANCH_ID, PAGEABLE))
                .thenReturn(Page.empty(PAGEABLE));

        service.listForBranch(null, PAGEABLE);

        // Active branch from the principal, never the assignment-resolved path.
        verify(locations).findByCompanyIdAndBranchId(COMPANY_ID, BRANCH_ID, PAGEABLE);
        verify(branches, never()).findByUid(any());
    }

    @Test
    void listForBranch_withBranchUid_listsThatBranch_whenAssigned() {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        Branch other = mock(Branch.class);
        when(other.getId()).thenReturn(OTHER_BRANCH_ID);
        when(other.getCompany()).thenReturn(company);
        when(branches.findByUid("OTHER-BRANCH-UID")).thenReturn(Optional.of(other));
        // Non-root caller is actively assigned to the requested branch.
        when(userBranches.findByUserIdAndBranchId(USER_ID, OTHER_BRANCH_ID))
                .thenReturn(Optional.of(mock(UserBranch.class)));
        when(locations.findByCompanyIdAndBranchId(COMPANY_ID, OTHER_BRANCH_ID, PAGEABLE))
                .thenReturn(Page.empty(PAGEABLE));

        service.listForBranch("OTHER-BRANCH-UID", PAGEABLE);

        // Lists the REQUESTED branch, not the active one.
        verify(locations).findByCompanyIdAndBranchId(COMPANY_ID, OTHER_BRANCH_ID, PAGEABLE);
    }

    @Test
    void listForBranch_withBranchUid_forbidden_whenNotAssigned() {
        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        Branch other = mock(Branch.class);
        when(other.getId()).thenReturn(OTHER_BRANCH_ID);
        when(other.getCompany()).thenReturn(company);
        when(branches.findByUid("OTHER-BRANCH-UID")).thenReturn(Optional.of(other));
        // Same company (assertCanActIn passes) but NOT assigned to the branch.
        when(userBranches.findByUserIdAndBranchId(USER_ID, OTHER_BRANCH_ID))
                .thenReturn(Optional.empty());

        org.junit.jupiter.api.Assertions.assertThrows(ForbiddenException.class,
                () -> service.listForBranch("OTHER-BRANCH-UID", PAGEABLE));

        verify(locations, never()).findByCompanyIdAndBranchId(any(), any(), any());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private StockLocation stubNewLocation(boolean isDefault) {
        StockLocation loc = mock(StockLocation.class);
        when(loc.getId()).thenReturn(200L);
        when(loc.getUid()).thenReturn("LOC-UID-001");
        when(loc.getCompanyId()).thenReturn(COMPANY_ID);
        when(loc.getBranchId()).thenReturn(BRANCH_ID);
        when(loc.getCode()).thenReturn("LOC-001");
        when(loc.getName()).thenReturn("Main Warehouse");
        when(loc.getLocationType()).thenReturn(LocationType.WAREHOUSE);
        when(loc.isDefault()).thenReturn(isDefault);
        when(loc.getStatus()).thenReturn(com.erp.platform.common.domain.MasterStatus.ACTIVE);
        return loc;
    }
}
