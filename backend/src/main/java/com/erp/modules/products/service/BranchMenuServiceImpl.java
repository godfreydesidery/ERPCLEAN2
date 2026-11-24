package com.erp.modules.products.service;

import com.erp.modules.products.repository.ProductBranchRepository;
import com.erp.platform.common.api.ConflictException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** See {@link BranchMenuService}. Read-only; callers have already scoped the product to a company. */
@Service
@Transactional(readOnly = true)
public class BranchMenuServiceImpl implements BranchMenuService {

    private final ProductBranchRepository productBranches;

    public BranchMenuServiceImpl(ProductBranchRepository productBranches) {
        this.productBranches = productBranches;
    }

    @Override
    public boolean isSoldAt(Long productId, Long branchId) {
        if (productId == null || branchId == null) {
            return true;
        }
        return productBranches.isSoldAt(productId, branchId).orElse(true);
    }

    @Override
    public void assertSoldAt(Long productId, Long branchId, String productName) {
        if (!isSoldAt(productId, branchId)) {
            throw new ConflictException(
                    (productName == null || productName.isBlank() ? "This item" : productName)
                            + " is not sold at this branch. A manager can turn it on in Product Master"
                            + " (Branch availability).");
        }
    }
}
