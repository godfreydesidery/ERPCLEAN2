package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.products.domain.dto.BomCostRollUpDto;
import com.erp.modules.products.domain.dto.BomExplosionLeafDto;
import com.erp.modules.products.domain.dto.BomExplosionResultDto;
import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.repository.BomComponentRepository;
import com.erp.modules.products.repository.BomRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * Unit tests proving that {@link BomExplosionServiceImpl} and {@link BomCostRollUpServiceImpl}
 * enforce {@code ScopeGuard.assertCanActIn} on every read path (ADR-0026 NFR-BOM-06 / D-11).
 *
 * <p>Regression guard for adversarial-review findings 1, 2, 3 (BLOCKER/HIGH):
 * a cross-tenant caller must receive a {@link ForbiddenException}, not BOM data.
 */
class BomScopeGuardTest {

    // ─── shared mocks ──────────────────────────────────────────────────────────

    private final BomRepository         bomsRepo       = mock(BomRepository.class);
    private final BomComponentRepository bomCompsRepo   = mock(BomComponentRepository.class);
    private final ProductRepository     productsRepo   = mock(ProductRepository.class);
    private final ScopeGuard            scopeGuard     = mock(ScopeGuard.class);
    private final BomCostRollUpService  costRollUp     = mock(BomCostRollUpService.class);

    private final BomExplosionServiceImpl explosionSvc = new BomExplosionServiceImpl(
            bomsRepo, bomCompsRepo, productsRepo, costRollUp, scopeGuard, 20);

    // ─── cost-roll-up dependencies ─────────────────────────────────────────────

    private final BomExplosionService   explosionPort   = mock(BomExplosionService.class);
    private final LeafCostResolver      leafCostResolver = mock(LeafCostResolver.class);
    private final BranchRepository      branchesRepo    = mock(BranchRepository.class);

    private final BomCostRollUpServiceImpl costRollUpSvc = new BomCostRollUpServiceImpl(
            explosionPort, leafCostResolver, bomsRepo, productsRepo, branchesRepo, scopeGuard);

    // ─── test data ─────────────────────────────────────────────────────────────

    private static final Long COMPANY_A = 10L;
    private static final Long COMPANY_B = 20L;  // attacker's company
    private static final Long BRANCH_ID = 5L;
    private static final String BOM_UID  = "bom-uid-tenant-a";

    @BeforeEach
    void setUp() {
        // Default: caller is in COMPANY_B (the attacker)
        RequestContext.set(new RequestContext.Principal(99L, "attacker", false, COMPANY_B, BRANCH_ID, null));
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // ─── helper: build a Bom owned by COMPANY_A ────────────────────────────────

    private Bom bomInCompanyA() {
        // Use the constructor; uid set via reflection to mimic DB-assigned value
        Bom bom = new Bom(COMPANY_A, 1L, 1, BigDecimal.ONE, BigDecimal.valueOf(100), null, null, null);
        try {
            var f = com.erp.platform.common.domain.UidEntity.class.getDeclaredField("uid");
            f.setAccessible(true);
            f.set(bom, BOM_UID);
            var fId = com.erp.platform.common.domain.UidEntity.class.getDeclaredField("id");
            fId.setAccessible(true);
            fId.set(bom, 1L);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return bom;
    }

    // ─── BomExplosionServiceImpl.explode() guard ──────────────────────────────

    @Nested
    class ExplodeGuard {

        @Test
        void explode_byBomUid_crossTenant_throws403() {
            Bom bom = bomInCompanyA();
            when(bomsRepo.findByUid(BOM_UID)).thenReturn(Optional.of(bom));
            doThrow(ForbiddenException.notPermitted())
                    .when(scopeGuard).assertCanActIn(any(), anyLong());

            assertThatThrownBy(() ->
                    explosionSvc.explode(null, BOM_UID, BigDecimal.ONE, true, null, null, false)
            ).isInstanceOf(ForbiddenException.class);

            // The guard was called with the BOM's owning companyId
            verify(scopeGuard).assertCanActIn(
                    RequestContext.get(), COMPANY_A);
        }

        @Test
        void explode_byBomUid_sameTenant_noException() {
            // Re-set context to COMPANY_A (same tenant as the BOM)
            RequestContext.set(new RequestContext.Principal(1L, "owner", false, COMPANY_A, BRANCH_ID, null));

            Bom bom = bomInCompanyA();
            when(bomsRepo.findByUid(BOM_UID)).thenReturn(Optional.of(bom));
            doNothing().when(scopeGuard).assertCanActIn(any(), anyLong());
            when(bomCompsRepo.findByBomIdOrderByLineNo(1L)).thenReturn(List.of());

            // Should not throw
            BomExplosionResultDto result = explosionSvc.explode(
                    null, BOM_UID, BigDecimal.ONE, true, null, null, false);
            assertThat(result).isNotNull();

            verify(scopeGuard).assertCanActIn(
                    RequestContext.get(), COMPANY_A);
        }

        @Test
        void explode_guardCalledBeforeAnyDataReturn() {
            // Prove the guard fires first — even if the bom repo returns data,
            // a 403 from the guard means no explosion data leaks out.
            Bom bom = bomInCompanyA();
            when(bomsRepo.findByUid(BOM_UID)).thenReturn(Optional.of(bom));
            doThrow(ForbiddenException.notPermitted())
                    .when(scopeGuard).assertCanActIn(any(), anyLong());

            assertThatThrownBy(() ->
                    explosionSvc.explode(null, BOM_UID, BigDecimal.ONE, false, null, null, false)
            ).isInstanceOf(ForbiddenException.class);

            // The component repository must NOT have been consulted (data-leak guard)
            verify(bomCompsRepo, never()).findByBomIdOrderByLineNo(anyLong());
        }
    }

    // ─── BomExplosionServiceImpl.explodeToLeaves() guard ─────────────────────

    @Nested
    class ExplodeToLeavesGuard {

        @Test
        void explodeToLeaves_crossTenant_throws403() {
            Bom bom = bomInCompanyA();
            when(bomsRepo.findByUid(BOM_UID)).thenReturn(Optional.of(bom));
            // resolveBom uses parentProductUid path — needs a product stub
            com.erp.modules.products.domain.entity.Product product =
                    mock(com.erp.modules.products.domain.entity.Product.class);
            when(product.getId()).thenReturn(1L);
            when(product.getCompanyId()).thenReturn(COMPANY_A);
            when(productsRepo.findByUid("parent-uid")).thenReturn(Optional.of(product));
            when(bomsRepo.findByParentProductIdAndStatus(1L, BomStatus.ACTIVE))
                    .thenReturn(Optional.of(bom));
            doThrow(ForbiddenException.notPermitted())
                    .when(scopeGuard).assertCanActIn(any(), anyLong());

            assertThatThrownBy(() ->
                    explosionSvc.explodeToLeaves("parent-uid", BigDecimal.ONE, false)
            ).isInstanceOf(ForbiddenException.class);

            verify(scopeGuard).assertCanActIn(RequestContext.get(), COMPANY_A);
        }

        @Test
        void explodeToLeaves_guardCalledBeforeComponentRead() {
            com.erp.modules.products.domain.entity.Product product =
                    mock(com.erp.modules.products.domain.entity.Product.class);
            when(product.getId()).thenReturn(1L);
            when(product.getCompanyId()).thenReturn(COMPANY_A);
            when(productsRepo.findByUid("parent-uid")).thenReturn(Optional.of(product));
            Bom bom = bomInCompanyA();
            when(bomsRepo.findByParentProductIdAndStatus(1L, BomStatus.ACTIVE))
                    .thenReturn(Optional.of(bom));
            doThrow(ForbiddenException.notPermitted())
                    .when(scopeGuard).assertCanActIn(any(), anyLong());

            assertThatThrownBy(() ->
                    explosionSvc.explodeToLeaves("parent-uid", BigDecimal.ONE, false)
            ).isInstanceOf(ForbiddenException.class);

            verify(bomCompsRepo, never()).findByBomIdOrderByLineNo(anyLong());
        }
    }

    // ─── BomCostRollUpServiceImpl.rollUp() guard ─────────────────────────────

    @Nested
    class CostRollUpGuard {

        @Test
        void rollUp_crossTenantBranch_throws403() {
            // Branch belongs to COMPANY_A; caller is in COMPANY_B
            Company companyA = mock(Company.class);
            when(companyA.getId()).thenReturn(COMPANY_A);
            Branch branchA = mock(Branch.class);
            when(branchA.getCompany()).thenReturn(companyA);
            when(branchA.getId()).thenReturn(BRANCH_ID);
            when(branchesRepo.findByUid("branch-a-uid")).thenReturn(Optional.of(branchA));

            doThrow(ForbiddenException.notPermitted())
                    .when(scopeGuard).assertCanActIn(any(), anyLong());

            assertThatThrownBy(() ->
                    costRollUpSvc.rollUp("parent-uid", null, "branch-a-uid", BigDecimal.ONE)
            ).isInstanceOf(ForbiddenException.class);

            verify(scopeGuard).assertCanActIn(RequestContext.get(), COMPANY_A);
        }

        @Test
        void rollUp_crossTenantBranch_noExplosionDataLeaks() {
            // Prove the guard fires BEFORE the explosion is called
            Company companyA = mock(Company.class);
            when(companyA.getId()).thenReturn(COMPANY_A);
            Branch branchA = mock(Branch.class);
            when(branchA.getCompany()).thenReturn(companyA);
            when(branchA.getId()).thenReturn(BRANCH_ID);
            when(branchesRepo.findByUid("branch-a-uid")).thenReturn(Optional.of(branchA));

            doThrow(ForbiddenException.notPermitted())
                    .when(scopeGuard).assertCanActIn(any(), anyLong());

            assertThatThrownBy(() ->
                    costRollUpSvc.rollUp("parent-uid", null, "branch-a-uid", BigDecimal.ONE)
            ).isInstanceOf(ForbiddenException.class);

            // The explosion service must NOT be called; avg_cost data must not leak
            verify(explosionPort, never()).explodeToLeaves(any(), any(), any(boolean.class));
            verify(leafCostResolver, never()).avgCosts(anyLong(), anyLong(), anyList());
        }

        @Test
        void rollUp_sameTenant_computesCostAndReturnsResult() {
            RequestContext.set(new RequestContext.Principal(1L, "owner", false, COMPANY_A, BRANCH_ID, null));

            Company companyA = mock(Company.class);
            when(companyA.getId()).thenReturn(COMPANY_A);
            Branch branchA = mock(Branch.class);
            when(branchA.getCompany()).thenReturn(companyA);
            when(branchA.getId()).thenReturn(BRANCH_ID);
            when(branchesRepo.findByUid("branch-a-uid")).thenReturn(Optional.of(branchA));

            doNothing().when(scopeGuard).assertCanActIn(any(), anyLong());

            BomExplosionLeafDto leaf = new BomExplosionLeafDto(
                    42L, "leaf-uid", "MAT-01", "Raw Material", BigDecimal.valueOf(3));
            when(explosionPort.explodeToLeaves("parent-uid", BigDecimal.ONE, true))
                    .thenReturn(List.of(leaf));
            when(leafCostResolver.avgCosts(COMPANY_A, BRANCH_ID, List.of(42L)))
                    .thenReturn(Map.of(42L, BigDecimal.valueOf(10)));

            BomCostRollUpDto result = costRollUpSvc.rollUp(
                    "parent-uid", null, "branch-a-uid", BigDecimal.ONE);

            // 3 units * $10 = $30.0000
            assertThat(result.standardCostAmount()).isEqualByComparingTo("30.0000");
            assertThat(result.complete()).isTrue();
            assertThat(result.incompleteLeaves()).isEmpty();
        }

        @Test
        void rollUp_byBomUid_crossTenantBranch_throws403() {
            // When only bomUid is supplied (parentProductUid null), branch guard still fires
            Bom bom = bomInCompanyA();
            when(bomsRepo.findByUid(BOM_UID)).thenReturn(Optional.of(bom));
            com.erp.modules.products.domain.entity.Product product =
                    mock(com.erp.modules.products.domain.entity.Product.class);
            when(product.getUid()).thenReturn("parent-uid");
            when(productsRepo.findById(1L)).thenReturn(Optional.of(product));

            Company companyA = mock(Company.class);
            when(companyA.getId()).thenReturn(COMPANY_A);
            Branch branchA = mock(Branch.class);
            when(branchA.getCompany()).thenReturn(companyA);
            when(branchA.getId()).thenReturn(BRANCH_ID);
            when(branchesRepo.findByUid("branch-a-uid")).thenReturn(Optional.of(branchA));

            doThrow(ForbiddenException.notPermitted())
                    .when(scopeGuard).assertCanActIn(any(), anyLong());

            assertThatThrownBy(() ->
                    costRollUpSvc.rollUp(null, BOM_UID, "branch-a-uid", BigDecimal.ONE)
            ).isInstanceOf(ForbiddenException.class);

            verify(scopeGuard).assertCanActIn(RequestContext.get(), COMPANY_A);
        }
    }
}
