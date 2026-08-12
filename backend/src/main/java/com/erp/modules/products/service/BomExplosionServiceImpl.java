package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.BomCostRollUpDto;
import com.erp.modules.products.domain.dto.BomExplosionLeafDto;
import com.erp.modules.products.domain.dto.BomExplosionNodeDto;
import com.erp.modules.products.domain.dto.BomExplosionResultDto;
import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.entity.BomComponent;
import com.erp.modules.products.domain.entity.Product;
import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import com.erp.modules.products.repository.BomComponentRepository;
import com.erp.modules.products.repository.BomRepository;
import com.erp.modules.products.repository.ProductRepository;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Multi-level BOM explosion (ADR-0026 D-6, FR-BOM-12/13, NFR-BOM-02/03/04).
 *
 * <p><strong>Arithmetic (D-4):</strong>
 * <pre>
 *   parentMultiplier  = outputQty / bom.output_qty
 *   headerInflate     = 100 / bom.yield_percent
 *   lineScrapInflate  = 100 / (100 − component.scrap_percent)
 *   effectiveChildQty = qty_per × parentMultiplier × headerInflate × lineScrapInflate
 * </pre>
 * Quantities carried at full BigDecimal precision; rounded HALF_UP to 6 dp only on presentation.
 *
 * <p><strong>N+1 avoidance (NFR-BOM-02):</strong> per-level batch reads via
 * {@code BomComponentRepository.findByBomIdIn} and
 * {@code BomRepository.findActiveByParentProductIdIn}.
 *
 * <p><strong>Depth guard (BR-BOM-11, NFR-BOM-03):</strong> fails fast at {@code maxDepth} with a
 * clear error rather than recursing unbounded.
 *
 * <p><strong>Single explosion implementation (NFR-BOM-04):</strong> this impl is the one explosion
 * code path; {@code RecipeExplosionResolver} delegates here when an ACTIVE BOM exists (D-7).
 */
@Service
@Transactional(readOnly = true)
public class BomExplosionServiceImpl implements BomExplosionService {

    private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
    private static final int DISPLAY_SCALE = 6;
    private static final RoundingMode HALF_UP = RoundingMode.HALF_UP;

    private final BomRepository boms;
    private final BomComponentRepository bomComponents;
    private final ProductRepository products;
    private final BomCostRollUpService costRollUp;
    private final ScopeGuard scopeGuard;
    private final int maxDepth;

    public BomExplosionServiceImpl(BomRepository boms,
                                   BomComponentRepository bomComponents,
                                   ProductRepository products,
                                   @Lazy BomCostRollUpService costRollUp,
                                   ScopeGuard scopeGuard,
                                   @Value("${bom.max-explosion-depth:20}") int maxDepth) {
        this.boms = boms;
        this.bomComponents = bomComponents;
        this.products = products;
        this.costRollUp = costRollUp;
        this.scopeGuard = scopeGuard;
        this.maxDepth = maxDepth;
    }

    @Override
    public BomExplosionResultDto explode(String parentProductUid,
                                         String bomUid,
                                         BigDecimal outputQty,
                                         boolean multiLevel,
                                         LocalDate asOfDate,
                                         String branchUid,
                                         boolean withCost) {
        // 1. Resolve the BOM version
        Bom bom = resolveBom(parentProductUid, bomUid, asOfDate);
        // NFR-BOM-06 / D-11: tenant isolation guard — reject cross-tenant read attempts
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());
        String resolvedParentUid = parentProductUid != null ? parentProductUid
                : products.findById(bom.getParentProductId())
                          .map(p -> p.getUid())
                          .orElse(null);

        // 2. Compute the top-level multiplier: how many BOM executions the request needs
        BigDecimal multiplier = outputQty.divide(bom.getOutputQty(), 15, HALF_UP);
        // Header yield inflate: ÷ (yield/100)
        BigDecimal headerInflate = HUNDRED.divide(bom.getYieldPercent(), 15, HALF_UP);
        BigDecimal topMultiplier = multiplier.multiply(headerInflate);

        // 3. Recurse
        Map<Long, LeafAccumulator> leafMap = new LinkedHashMap<>();
        List<BomExplosionNodeDto> tree = explodeLevel(
                bom, topMultiplier, 0, multiLevel, leafMap, bom.getCompanyId());

        // 4. Build leaf summary
        List<BomExplosionLeafDto> leaves = buildLeafSummary(leafMap);

        // 5. Optional cost roll-up
        BomCostRollUpDto costDto = null;
        if (withCost && branchUid != null) {
            costDto = costRollUp.rollUp(resolvedParentUid, bom.getUid(), branchUid, outputQty);
        }

        return new BomExplosionResultDto(bom.getUid(), resolvedParentUid, outputQty, tree, leaves, costDto);
    }

    @Override
    public List<BomExplosionLeafDto> explodeToLeaves(String parentProductUid,
                                                      BigDecimal outputQty,
                                                      boolean multiLevel) {
        Bom bom = resolveBom(parentProductUid, null, null);
        // NFR-BOM-06 / D-11: tenant isolation guard on leaf-only explosion path
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());
        return explodeFrom(bom, outputQty, multiLevel);
    }

    @Override
    public List<BomExplosionLeafDto> explodeBomToLeaves(String bomUid,
                                                         BigDecimal outputQty,
                                                         boolean multiLevel) {
        // The caller named a version, so no ACTIVE re-resolution happens here — substituting the
        // active version would hand back a different recipe under the requested label.
        Bom bom = Lookups.orNotFound(boms.findByUid(bomUid), "Bom", bomUid);
        // NFR-BOM-06 / D-11: tenant isolation guard on leaf-only explosion path
        scopeGuard.assertCanActIn(RequestContext.get(), bom.getCompanyId());
        return explodeFrom(bom, outputQty, multiLevel);
    }

    @Override
    public boolean hasActiveBom(String parentProductUid) {
        return products.findByUid(parentProductUid)
                .map(p -> boms.existsByParentProductIdAndStatus(p.getId(), BomStatus.ACTIVE))
                .orElse(false);
    }

    // -------------------------------------------------------------------------
    // Internal explosion logic
    // -------------------------------------------------------------------------

    /** Shared leaf-only explosion body — the caller has already resolved and guarded {@code bom}. */
    private List<BomExplosionLeafDto> explodeFrom(Bom bom, BigDecimal outputQty, boolean multiLevel) {
        BigDecimal multiplier = outputQty.divide(bom.getOutputQty(), 15, HALF_UP);
        BigDecimal headerInflate = HUNDRED.divide(bom.getYieldPercent(), 15, HALF_UP);
        BigDecimal topMultiplier = multiplier.multiply(headerInflate);

        Map<Long, LeafAccumulator> leafMap = new LinkedHashMap<>();
        explodeLevel(bom, topMultiplier, 0, multiLevel, leafMap, bom.getCompanyId());
        return buildLeafSummary(leafMap);
    }

    private List<BomExplosionNodeDto> explodeLevel(Bom bom,
                                                    BigDecimal levelMultiplier,
                                                    int depth,
                                                    boolean multiLevel,
                                                    Map<Long, LeafAccumulator> leafMap,
                                                    Long companyId) {
        if (depth > maxDepth) {
            // BR-BOM-11: max nesting depth exceeded during explosion
            throw new IllegalStateException(
                    "The bill of materials exceeds the maximum allowed nesting depth (" + maxDepth + "). "
                    + "Please review and simplify the BOM structure.");
        }

        List<BomComponent> components = bomComponents.findByBomIdOrderByLineNo(bom.getId());
        if (components.isEmpty()) {
            return List.of();
        }

        // Batch: fetch active BOMs for MAKE children (O(1) query per level — NFR-BOM-02)
        List<Long> makeChildIds = components.stream()
                .filter(c -> ComponentSourcing.MAKE.equals(c.getSourcing()))
                .map(BomComponent::getComponentProductId)
                .distinct()
                .toList();
        Map<Long, Bom> activeBomByProductId = makeChildIds.isEmpty() ? Map.of()
                : boms.findActiveByParentProductIdIn(companyId, makeChildIds).stream()
                      .collect(Collectors.toMap(Bom::getParentProductId, b -> b));

        List<BomExplosionNodeDto> nodes = new ArrayList<>(components.size());
        for (BomComponent comp : components) {
            BigDecimal lineScrapInflate = scrapInflate(comp.getScrapPercent());
            BigDecimal effectiveQty = comp.getQtyPer()
                    .multiply(levelMultiplier)
                    .multiply(lineScrapInflate);

            boolean isMake = ComponentSourcing.MAKE.equals(comp.getSourcing());
            Bom childBom = isMake ? activeBomByProductId.get(comp.getComponentProductId()) : null;
            boolean recurse = multiLevel && isMake && childBom != null;
            boolean isLeaf = !recurse;

            if (isLeaf) {
                // Accumulate into leaf summary (keyed by product id)
                leafMap.computeIfAbsent(comp.getComponentProductId(),
                        id -> new LeafAccumulator(comp))
                       .add(effectiveQty);
            }

            List<BomExplosionNodeDto> children;
            if (recurse) {
                // Compute child-level multiplier: effectiveQty already includes parent's scrap/yield.
                // The child BOM has its own output_qty and yield; pass effectiveQty as the
                // "requested output" so the child multiplier = effectiveQty / child.output_qty.
                BigDecimal childMultiplier = effectiveQty
                        .divide(childBom.getOutputQty(), 15, HALF_UP)
                        .multiply(HUNDRED.divide(childBom.getYieldPercent(), 15, HALF_UP));
                children = explodeLevel(childBom, childMultiplier, depth + 1, true, leafMap, companyId);
            } else {
                children = List.of();
            }

            nodes.add(new BomExplosionNodeDto(
                    depth,
                    comp.getComponentProductId(),
                    null, // uid resolved lazily — not needed for explosion, saves a join
                    comp.getComponentProductCode(),
                    comp.getComponentProductName(),
                    comp.getSourcing(),
                    effectiveQty.setScale(DISPLAY_SCALE, HALF_UP),
                    isLeaf,
                    children
            ));
        }
        return nodes;
    }

    private List<BomExplosionLeafDto> buildLeafSummary(Map<Long, LeafAccumulator> leafMap) {
        if (leafMap.isEmpty()) {
            return List.of();
        }
        // Batch-load uids for all leaf product ids in one query (avoids N+1 on uid resolution)
        List<Long> ids = leafMap.keySet().stream().toList();
        Map<Long, String> uidById = products.findAllById(ids).stream()
                .collect(Collectors.toMap(Product::getId, Product::getUid));

        return leafMap.values().stream()
                .map(acc -> new BomExplosionLeafDto(
                        acc.productId,
                        uidById.get(acc.productId),
                        acc.code,
                        acc.name,
                        acc.total.setScale(DISPLAY_SCALE, HALF_UP)))
                .toList();
    }

    private Bom resolveBom(String parentProductUid, String bomUid, LocalDate asOfDate) {
        if (bomUid != null) {
            Bom bom = Lookups.orNotFound(boms.findByUid(bomUid), "Bom", bomUid);
            // A named version wins over any parent hint, but the two must describe the same
            // product — otherwise the result carries one product's label and another's recipe.
            if (parentProductUid != null) {
                var parent = Lookups.orNotFound(
                        products.findByUid(parentProductUid), "Product", parentProductUid);
                if (!parent.getId().equals(bom.getParentProductId())) {
                    throw new IllegalArgumentException(
                            "The selected bill of materials does not belong to the requested product.");
                }
            }
            return bom;
        }
        if (parentProductUid == null) {
            throw new IllegalArgumentException("Either parentProductUid or bomUid must be supplied.");
        }
        var product = Lookups.orNotFound(products.findByUid(parentProductUid), "Product", parentProductUid);
        Long parentProductId = product.getId();
        Long companyId = product.getCompanyId();

        if (asOfDate != null) {
            List<Bom> effective = boms.findEffective(companyId, parentProductId, asOfDate);
            if (!effective.isEmpty()) {
                return effective.get(0);
            }
        }
        return boms.findByParentProductIdAndStatus(parentProductId, BomStatus.ACTIVE)
                .orElseThrow(() -> new NotFoundException(
                        "No active bill of materials found for this product."));
    }

    private static BigDecimal scrapInflate(BigDecimal scrapPercent) {
        if (scrapPercent == null || scrapPercent.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ONE;
        }
        // ÷ (1 − scrap%) = ÷ ((100 − scrapPercent) / 100) = 100 / (100 − scrapPercent) (D-4)
        BigDecimal denominator = HUNDRED.subtract(scrapPercent);
        return HUNDRED.divide(denominator, 15, HALF_UP);
    }

    // -------------------------------------------------------------------------
    // Leaf accumulator (mutable, used only within this class)
    // -------------------------------------------------------------------------

    private static class LeafAccumulator {
        final Long productId;
        final String code;
        final String name;
        BigDecimal total = BigDecimal.ZERO;

        LeafAccumulator(BomComponent comp) {
            this.productId = comp.getComponentProductId();
            this.code = comp.getComponentProductCode();
            this.name = comp.getComponentProductName();
        }

        void add(BigDecimal qty) {
            total = total.add(qty);
        }
    }
}
