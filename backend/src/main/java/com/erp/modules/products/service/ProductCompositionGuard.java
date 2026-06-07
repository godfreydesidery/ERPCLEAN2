package com.erp.modules.products.service;

import com.erp.modules.products.domain.entity.Product;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.domain.MasterStatus;
import org.springframework.stereotype.Component;

/**
 * Enforces BR-PROD-05 and BR-PROD-06 for composition:
 * <ul>
 *   <li>BR-PROD-06: a component must belong to the same company as the composed product.</li>
 *   <li>BR-PROD-05 (non-self part): a component may not be ARCHIVED when added.</li>
 * </ul>
 * Self-composition (BR-PROD-05 self-ref) is a DB CHECK ({@code chk_product_component_not_self}).
 */
@Component
public class ProductCompositionGuard {

    /**
     * Asserts the component is from the same company as the composed product, and is not ARCHIVED.
     *
     * @param composed  the product that will contain the component
     * @param component the product being added as a component
     * @throws ForbiddenException       if different companies (BR-PROD-06)
     * @throws IllegalArgumentException if the component is ARCHIVED (BR-PROD-05)
     */
    public void assertCanAddComponent(Product composed, Product component) {
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
