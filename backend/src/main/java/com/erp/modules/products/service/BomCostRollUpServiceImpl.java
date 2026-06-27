package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.BomCostRollUpDto;
import com.erp.modules.products.domain.dto.BomExplosionLeafDto;
import com.erp.modules.products.repository.BomRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.modules.iam.repository.BranchRepository;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Standard-cost roll-up implementation (ADR-0026 D-8, BR-BOM-09).
 *
 * <p>Explodes the BOM to leaf summary, then calls {@link LeafCostResolver} (the port declared in
 * {@code products}, implemented in {@code stock}) to batch-fetch avg_cost. The
 * dependency-inversion port (D-10) keeps the module graph acyclic — no {@code products→stock}
 * import.
 *
 * <p>Currency: base currency from company config (ADR-0005). This service reads the company's
 * default currency via the product's company — simple lookup, no GL config read.
 */
@Service
@Transactional(readOnly = true)
public class BomCostRollUpServiceImpl implements BomCostRollUpService {

    private static final int COST_SCALE = 4;
    private static final RoundingMode HALF_UP = RoundingMode.HALF_UP;

    private final BomExplosionService explosion;
    private final LeafCostResolver leafCostResolver;
    private final BomRepository boms;
    private final ProductRepository products;
    private final BranchRepository branches;
    private final ScopeGuard scopeGuard;

    public BomCostRollUpServiceImpl(BomExplosionService explosion,
                                    LeafCostResolver leafCostResolver,
                                    BomRepository boms,
                                    ProductRepository products,
                                    BranchRepository branches,
                                    ScopeGuard scopeGuard) {
        this.explosion = explosion;
        this.leafCostResolver = leafCostResolver;
        this.boms = boms;
        this.products = products;
        this.branches = branches;
        this.scopeGuard = scopeGuard;
    }

    @Override
    public BomCostRollUpDto rollUp(String parentProductUid, String bomUid, String branchUid,
                                   BigDecimal outputQty) {
        // 1. Resolve the parent product uid for the explosion
        String resolvedParentUid = parentProductUid;
        String resolvedBomUid = bomUid;

        if (resolvedParentUid == null && bomUid != null) {
            var bom = Lookups.orNotFound(boms.findByUid(bomUid), "Bom", bomUid);
            resolvedParentUid = products.findById(bom.getParentProductId())
                    .map(p -> p.getUid())
                    .orElseThrow(() -> new NotFoundException("Product not found for BOM."));
            resolvedBomUid = bom.getUid();
        }

        // 2. Resolve branch to companyId + branchId.
        // Use findWithCompanyByUid (EntityGraph) so branch.company is eagerly loaded — avoids
        // LazyInitializationException when getCompany().getId() is called (MANUFACTURING-022).
        var branch = branches.findWithCompanyByUid(branchUid)
                .orElseThrow(() -> new NotFoundException("Branch not found."));
        Long companyId = branch.getCompany().getId();
        Long branchId = branch.getId();
        // NFR-BOM-06 / D-11: tenant isolation guard — reject cross-tenant cost reads
        // avg_cost is sensitive inventory data; must match the caller's active company.
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // 3. Explode to leaves (multi-level = true for cost roll-up)
        List<BomExplosionLeafDto> leaves = explosion.explodeToLeaves(
                resolvedParentUid, outputQty, true);

        if (leaves.isEmpty()) {
            return new BomCostRollUpDto(
                    resolvedParentUid, resolvedBomUid, branchUid, outputQty,
                    BigDecimal.ZERO, null, true, List.of());
        }

        // 4. Batch-fetch avg_costs for all leaf product ids
        List<Long> leafProductIds = leaves.stream()
                .map(BomExplosionLeafDto::componentProductId)
                .filter(id -> id != null)
                .distinct()
                .toList();

        Map<Long, BigDecimal> avgCosts = leafCostResolver.avgCosts(companyId, branchId, leafProductIds);

        // 5. Accumulate: flagged incomplete if any leaf has no avg_cost (BR-BOM-09)
        BigDecimal total = BigDecimal.ZERO;
        List<BomCostRollUpDto.IncompleteLeafDto> incomplete = new ArrayList<>();

        for (BomExplosionLeafDto leaf : leaves) {
            if (leaf.componentProductId() == null) {
                continue;
            }
            BigDecimal cost = avgCosts.get(leaf.componentProductId());
            if (cost == null) {
                incomplete.add(new BomCostRollUpDto.IncompleteLeafDto(
                        leaf.componentProductUid(), leaf.componentProductName()));
            } else {
                total = total.add(leaf.totalQuantity().multiply(cost));
            }
        }

        boolean complete = incomplete.isEmpty();
        BigDecimal roundedTotal = total.setScale(COST_SCALE, HALF_UP);

        // Currency: derive from company (ADR-0005 base currency — no GL config read needed)
        // For now, use null currency (the company base currency is not in scope of this service;
        // Manufacturing that persists standard cost will resolve it via company config).
        String currency = null;

        return new BomCostRollUpDto(
                resolvedParentUid, resolvedBomUid, branchUid, outputQty,
                roundedTotal, currency, complete, incomplete);
    }
}
