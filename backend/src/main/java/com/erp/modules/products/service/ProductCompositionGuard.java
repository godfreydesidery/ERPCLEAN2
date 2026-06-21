package com.erp.modules.products.service;

import com.erp.modules.products.domain.entity.Product;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.domain.MasterStatus;
import org.springframework.stereotype.Component;

/**
 * Enforces BR-PROD-05 and BR-PROD-06 for composition:
 * <ul>
 *   <li>BR-PROD-05 (self-ref): a product cannot be a component of itself.</li>
 *   <li>BR-PROD-05 (archived): a component may not be ARCHIVED when added.</li>
 *   <li>BR-PROD-06: a component must belong to the same company as the composed product.</li>
 * </ul>
 * The self-ref guard here produces a clear domain message BEFORE the DB CHECK
 * ({@code chk_product_component_not_self}) fires a generic constraint error (defect 6).
 */
@Component
public class ProductCompositionGuard {

    /**
     * Asserts composition rules before persisting a component link.
     *
     * @param composed  the product that will contain the component
     * @param component the product being added as a component
     * @throws IllegalArgumentException if composed == component (BR-PROD-05 self-ref)
     * @throws ForbiddenException       if different companies (BR-PROD-06)
     * @throws IllegalArgumentException if the component is ARCHIVED (BR-PROD-05)
     */
    public void assertCanAddComponent(Product composed, Product component) {
        // Defect 6a: self-reference — clear message before the DB CHECK fires.
        if (composed.getId() != null && composed.getId().equals(component.getId())) {
            throw new IllegalArgumentException(
                    "A product cannot be a component of itself (BR-PROD-05).");
        }
        if (!component.getCompanyId().equals(composed.getCompanyId())) {
            throw new ForbiddenException(
                    "Component must belong to the same company as the composed product (BR-PROD-06).");
        }
        if (MasterStatus.ARCHIVED.equals(component.getStatus())) {
            throw new IllegalArgumentException(
                    "Cannot add an ARCHIVED product as a component (BR-PROD-05).");
        }
    }
}
