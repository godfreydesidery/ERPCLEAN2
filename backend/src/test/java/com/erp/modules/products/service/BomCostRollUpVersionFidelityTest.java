package com.erp.modules.products.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Branch;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.modules.products.domain.dto.BomCostRollUpDto;
import com.erp.modules.products.domain.dto.BomExplosionLeafDto;
import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.entity.BomComponent;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import com.erp.modules.products.repository.BomComponentRepository;
import com.erp.modules.products.repository.BomRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * BOM version fidelity on the cost roll-up (UAT 2026-08-12, defect D1).
 *
 * <p>{@code cost-roll-up?bomUid=X} used to resolve the parent product from BOM X and then explode
 * the parent's <em>ACTIVE</em> version — while echoing X back in the response. Costing a DRAFT or a
 * superseded version therefore returned the ACTIVE version's number under the wrong label, with no
 * way for the caller to detect the substitution.
 *
 * <p>The fixture deliberately gives every version a different single component at a different
 * cost, so "which version was costed" is readable straight off the amount:
 * <pre>
 *   v1 (superseded) : 2 × RM-A @ 10.00 =  20.0000
 *   v2 (ACTIVE)     : 5 × RM-B @ 100.00 = 500.0000
 *   v3 (DRAFT)      : 3 × RM-C @ 7.00   =  21.0000
 *   v4 (DRAFT)      : no components     — cannot be costed
 * </pre>
 */
class BomCostRollUpVersionFidelityTest {

    private static final Long COMPANY_ID    = 10L;
    private static final Long BRANCH_ID     = 5L;
    private static final Long USER_ID       = 7L;
    private static final Long FG_PRODUCT_ID = 1L;

    private static final String FG_UID         = "PRODFG0000000000000000001";
    private static final String OTHER_FG_UID   = "PRODFG0000000000000000002";
    private static final String BRANCH_UID     = "BRANCH00000000000000000001";
    private static final String BOM_V1_UID     = "BOMV1000000000000000000001";
    private static final String BOM_V2_UID     = "BOMV2000000000000000000002";
    private static final String BOM_V3_UID     = "BOMV3000000000000000000003";
    private static final String BOM_EMPTY_UID  = "BOMV4000000000000000000004";

    private static final Long RM_A_ID = 55L;
    private static final Long RM_B_ID = 56L;
    private static final Long RM_C_ID = 57L;

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

    private final BomRepository          bomsRepo      = mock(BomRepository.class);
    private final BomComponentRepository bomCompsRepo  = mock(BomComponentRepository.class);
    private final ProductRepository      productsRepo  = mock(ProductRepository.class);
    private final BranchRepository       branchesRepo  = mock(BranchRepository.class);
    private final LeafCostResolver       leafCosts     = mock(LeafCostResolver.class);
    private final ScopeGuard             scopeGuard    = mock(ScopeGuard.class);

    /** Real explosion service — the version-resolution logic is exactly what is under test. */
    private final BomExplosionServiceImpl explosion = new BomExplosionServiceImpl(
            bomsRepo, bomCompsRepo, productsRepo,
            mock(BomCostRollUpService.class), // only used by explode(withCost) — not exercised here
            scopeGuard, 20);

    private final BomCostRollUpServiceImpl rollUp = new BomCostRollUpServiceImpl(
            explosion, leafCosts, bomsRepo, productsRepo, branchesRepo, scopeGuard);

    @BeforeEach
    void setUp() {
        RequestContext.set(new RequestContext.Principal(
                USER_ID, "planner@test.com", false, COMPANY_ID, BRANCH_ID, null));
        doNothing().when(scopeGuard).assertCanActIn(any(), anyLong());

        Bom v1    = bom(101L, BOM_V1_UID, 1, BomStatus.ARCHIVED);   // superseded by v2
        Bom v2    = bom(102L, BOM_V2_UID, 2, BomStatus.ACTIVE);
        Bom v3    = bom(103L, BOM_V3_UID, 3, BomStatus.DRAFT);
        Bom empty = bom(104L, BOM_EMPTY_UID, 4, BomStatus.DRAFT);

        when(bomsRepo.findByUid(BOM_V1_UID)).thenReturn(Optional.of(v1));
        when(bomsRepo.findByUid(BOM_V2_UID)).thenReturn(Optional.of(v2));
        when(bomsRepo.findByUid(BOM_V3_UID)).thenReturn(Optional.of(v3));
        when(bomsRepo.findByUid(BOM_EMPTY_UID)).thenReturn(Optional.of(empty));
        when(bomsRepo.findByParentProductIdAndStatus(FG_PRODUCT_ID, BomStatus.ACTIVE))
                .thenReturn(Optional.of(v2));

        when(bomCompsRepo.findByBomIdOrderByLineNo(101L))
                .thenReturn(List.of(component(101L, RM_A_ID, "RM-A", "Raw A", new BigDecimal("2"))));
        when(bomCompsRepo.findByBomIdOrderByLineNo(102L))
                .thenReturn(List.of(component(102L, RM_B_ID, "RM-B", "Raw B", new BigDecimal("5"))));
        when(bomCompsRepo.findByBomIdOrderByLineNo(103L))
                .thenReturn(List.of(component(103L, RM_C_ID, "RM-C", "Raw C", new BigDecimal("3"))));
        when(bomCompsRepo.findByBomIdOrderByLineNo(104L)).thenReturn(List.of());

        Product fg = product(FG_PRODUCT_ID, FG_UID);
        when(productsRepo.findByUid(FG_UID)).thenReturn(Optional.of(fg));
        when(productsRepo.findById(FG_PRODUCT_ID)).thenReturn(Optional.of(fg));

        Map<Long, String> leafUids = Map.of(
                RM_A_ID, "PRODRMA000000000000000001",
                RM_B_ID, "PRODRMB000000000000000002",
                RM_C_ID, "PRODRMC000000000000000003");
        when(productsRepo.findAllById(anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(0);
            List<Product> found = new ArrayList<>();
            ids.forEach(id -> found.add(product(id, leafUids.get(id))));
            return found;
        });

        Company company = mock(Company.class);
        when(company.getId()).thenReturn(COMPANY_ID);
        Branch branch = mock(Branch.class);
        when(branch.getCompany()).thenReturn(company);
        when(branch.getId()).thenReturn(BRANCH_ID);
        when(branchesRepo.findWithCompanyByUid(BRANCH_UID)).thenReturn(Optional.of(branch));

        Map<Long, BigDecimal> avgCostByProduct = Map.of(
                RM_A_ID, new BigDecimal("10"),
                RM_B_ID, new BigDecimal("100"),
                RM_C_ID, new BigDecimal("7"));
        when(leafCosts.avgCosts(anyLong(), anyLong(), anyList())).thenAnswer(inv -> {
            List<Long> ids = inv.getArgument(2);
            Map<Long, BigDecimal> out = new HashMap<>();
            ids.forEach(id -> out.put(id, avgCostByProduct.get(id)));
            return out;
        });
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // D1 — a named version must be the version that is costed
    // -------------------------------------------------------------------------

    @Test
    void rollUp_namedSupersededVersion_costsThatVersion_notTheActiveOne() {
        BomCostRollUpDto result = rollUp.rollUp(null, BOM_V1_UID, BRANCH_UID, BigDecimal.ONE);

        // 2 × RM-A @ 10.00. The ACTIVE v2 would have produced 500.0000 — the old, silent answer.
        assertThat(result.standardCostAmount()).isEqualByComparingTo("20.0000");
        assertThat(result.complete()).isTrue();
    }

    @Test
    void rollUp_namedDraftVersion_costsTheDraft_notTheActiveOne() {
        BomCostRollUpDto result = rollUp.rollUp(null, BOM_V3_UID, BRANCH_UID, BigDecimal.ONE);

        // 3 × RM-C @ 7.00 = 21.0000 — a DRAFT is costable, it just must be costed as itself.
        assertThat(result.standardCostAmount()).isEqualByComparingTo("21.0000");
    }

    @Test
    void rollUp_echoesTheVersionAndProductItActuallyCosted() {
        BomCostRollUpDto result = rollUp.rollUp(null, BOM_V1_UID, BRANCH_UID, BigDecimal.ONE);

        // The echo is the contract that lets a caller detect a substitution — it must describe
        // the version whose components produced standardCostAmount.
        assertThat(result.bomUid()).isEqualTo(BOM_V1_UID);
        assertThat(result.parentProductUid()).isEqualTo(FG_UID);
        assertThat(result.branchUid()).isEqualTo(BRANCH_UID);
    }

    // -------------------------------------------------------------------------
    // Callers that do NOT name a version still get the ACTIVE one (unchanged)
    // -------------------------------------------------------------------------

    @Test
    void rollUp_withoutANamedVersion_stillResolvesTheActiveVersion() {
        BomCostRollUpDto result = rollUp.rollUp(FG_UID, null, BRANCH_UID, BigDecimal.ONE);

        // 5 × RM-B @ 100.00 — the ACTIVE v2. This path is deliberately untouched by the fix.
        assertThat(result.standardCostAmount()).isEqualByComparingTo("500.0000");
        assertThat(result.parentProductUid()).isEqualTo(FG_UID);
    }

    @Test
    void explodeToLeaves_withoutANamedVersion_stillResolvesTheActiveVersion() {
        List<BomExplosionLeafDto> leaves = explosion.explodeToLeaves(FG_UID, BigDecimal.ONE, true);

        assertThat(leaves).singleElement()
                .extracting(BomExplosionLeafDto::componentProductId)
                .isEqualTo(RM_B_ID);
    }

    // -------------------------------------------------------------------------
    // Refuse rather than substitute
    // -------------------------------------------------------------------------

    @Test
    void rollUp_namedVersionWithNoComponents_isRefused_notSilentlySubstituted() {
        assertThatThrownBy(() -> rollUp.rollUp(null, BOM_EMPTY_UID, BRANCH_UID, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("no components");
    }

    @Test
    void rollUp_namedVersionThatBelongsToAnotherProduct_isRefused() {
        // Product hint and version disagree: answering either one silently mislabels the result.
        assertThatThrownBy(() -> rollUp.rollUp(OTHER_FG_UID, BOM_V1_UID, BRANCH_UID, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not belong");
    }

    // -------------------------------------------------------------------------
    // Fixture helpers
    // -------------------------------------------------------------------------

    private static Bom bom(long id, String uid, int versionNo, BomStatus status) {
        // output_qty 1 and yield 100% keep the arithmetic a plain multiplication, so a wrong
        // amount can only mean a wrong version — not a rounding or inflation difference.
        Bom b = new Bom(COMPANY_ID, FG_PRODUCT_ID, versionNo, BigDecimal.ONE, HUNDRED,
                null, null, USER_ID);
        b.setStatus(status);
        ReflectionTestUtils.setField(b, "id", id);
        ReflectionTestUtils.setField(b, "uid", uid);
        return b;
    }

    private static BomComponent component(long bomId, Long productId, String code, String name,
                                          BigDecimal qtyPer) {
        return new BomComponent(bomId, COMPANY_ID, (short) 1, productId, code, name,
                qtyPer, ComponentSourcing.BUY, BigDecimal.ZERO, null, USER_ID);
    }

    private static Product product(Long id, String uid) {
        Product p = mock(Product.class);
        when(p.getId()).thenReturn(id);
        when(p.getUid()).thenReturn(uid);
        when(p.getCompanyId()).thenReturn(COMPANY_ID);
        return p;
    }
}
