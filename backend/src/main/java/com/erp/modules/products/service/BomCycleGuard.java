package com.erp.modules.products.service;

import com.erp.modules.products.domain.entity.Bom;
import com.erp.modules.products.domain.entity.BomComponent;
import com.erp.modules.products.domain.enums.BomStatus;
import com.erp.modules.products.domain.enums.ComponentSourcing;
import com.erp.modules.products.repository.BomComponentRepository;
import com.erp.modules.products.repository.BomRepository;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Transitive acyclic check for multi-level BOM structures (ADR-0026 D-6, BR-BOM-01).
 *
 * <p>The v1 single-level {@code product_components} has a DB CHECK
 * ({@code chk_product_component_not_self}) that catches only the one-level self-reference.
 * With multi-level nesting, A→B→A is a cycle that a single-row CHECK cannot see — it is a
 * graph reachability question across rows. This guard runs a depth-first traversal of the
 * candidate child's fully-exploded structure and rejects the add if {@code parentProductId}
 * appears anywhere in it.
 *
 * <p>The guard runs:
 * <ol>
 *   <li>Before persisting any {@link BomComponent} add ({@code BomComponentServiceImpl.add}).
 *   <li>As a whole-tree check at {@code BomServiceImpl.activate} (last-moment validation).
 * </ol>
 *
 * <p>Bounded by {@code bom.max-explosion-depth} (default 20) — the acyclic invariant guarantees
 * termination on well-formed structures; the bound defends against a mid-edit bad graph
 * (BR-BOM-11, NFR-BOM-03).
 */
@Component
public class BomCycleGuard {

    private final BomRepository boms;
    private final BomComponentRepository bomComponents;
    private final int maxDepth;

    public BomCycleGuard(BomRepository boms,
                         BomComponentRepository bomComponents,
                         @Value("${bom.max-explosion-depth:20}") int maxDepth) {
        this.boms = boms;
        this.bomComponents = bomComponents;
        this.maxDepth = maxDepth;
    }

    /**
     * Asserts that adding {@code candidateChildProductId} as a component of the BOM whose parent is
     * {@code parentProductId} would not create a cycle (BR-BOM-01).
     *
     * <p>Base case: {@code candidateChildProductId == parentProductId} ⇒ cycle (self-reference).
     * Recursive case: explode the candidate child's ACTIVE BOM; if {@code parentProductId} appears
     * anywhere in the child's structure, the add would close a loop — reject.
     *
     * @throws IllegalArgumentException if the add would create a cycle
     */
    public void assertNoCycle(Long parentProductId, Long candidateChildProductId) {
        if (parentProductId.equals(candidateChildProductId)) {
            throw new IllegalArgumentException(
                    "Cannot add a product as a component of itself (BR-BOM-01 self-reference).");
        }
        Set<Long> visited = new HashSet<>();
        visited.add(parentProductId); // the product we are checking against
        if (reachable(candidateChildProductId, parentProductId, visited, 0)) {
            throw new IllegalArgumentException(
                    "Adding this component would create a BOM cycle (BR-BOM-01): " +
                    "the candidate child's structure already contains the parent product.");
        }
    }

    /**
     * Depth-first reachability: does {@code targetProductId} appear in the fully-exploded structure
     * of {@code currentProductId} (using its ACTIVE BOM)?
     *
     * @param currentProductId the product whose ACTIVE BOM we are expanding
     * @param targetProductId  the product we are looking for
     * @param visited          set of already-expanded product ids (prevents re-expansion in DAGs)
     * @param depth            current recursion depth
     * @return true if {@code targetProductId} is reachable from {@code currentProductId}
     */
    private boolean reachable(Long currentProductId, Long targetProductId,
                               Set<Long> visited, int depth) {
        if (depth > maxDepth) {
            throw new IllegalStateException(
                    "BOM exceeds maximum nesting depth (" + maxDepth + ") during cycle check " +
                    "(BR-BOM-11). Check for a pathological or mid-edit structure.");
        }
        Optional<Bom> activeBom = boms.findByParentProductIdAndStatus(currentProductId, BomStatus.ACTIVE);
        if (activeBom.isEmpty()) {
            return false; // leaf — no ACTIVE BOM to recurse into
        }
        List<BomComponent> components = bomComponents.findByBomIdOrderByLineNo(activeBom.get().getId());
        for (BomComponent comp : components) {
            Long childId = comp.getComponentProductId();
            if (childId.equals(targetProductId)) {
                return true; // found the target
            }
            // Only MAKE components are recursed (BUY are leaves — their structure is not expanded)
            if (ComponentSourcing.MAKE.equals(comp.getSourcing()) && !visited.contains(childId)) {
                visited.add(childId);
                if (reachable(childId, targetProductId, visited, depth + 1)) {
                    return true;
                }
            }
        }
        return false;
    }
}
