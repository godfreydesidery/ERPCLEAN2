package com.erp.modules.products.service;

/**
 * Which items a branch sells (ADR-0073 D-1): an item with no {@code product_branch} rows is sold at
 * every branch of its company; an item with rows is sold only where its row is active.
 */
public interface BranchMenuService {

    /** Whether {@code productId} is sold at {@code branchId}. A null branch is "no branch in view": true. */
    boolean isSoldAt(Long productId, Long branchId);

    /**
     * Refuses (409) an item that is not sold at {@code branchId}, naming it.
     *
     * @param productName shown in the message, as the till knows it
     */
    void assertSoldAt(Long productId, Long branchId, String productName);
}
