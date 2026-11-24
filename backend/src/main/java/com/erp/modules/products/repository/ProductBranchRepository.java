package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.ProductBranch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProductBranchRepository extends JpaRepository<ProductBranch, Long> {

    List<ProductBranch> findByProductId(Long productId);

    Optional<ProductBranch> findByProductIdAndBranchId(Long productId, Long branchId);

    void deleteByProductIdAndBranchId(Long productId, Long branchId);

    /**
     * ADR-0073 D-1 in one query: an item with no branch rows is sold everywhere; an item with rows
     * is sold only where its row is active. Both probes use the unique (product_id, branch_id) index.
     */
    @Query("""
            SELECT CASE WHEN
                NOT EXISTS (SELECT 1 FROM ProductBranch a WHERE a.product.id = :productId)
                OR EXISTS (SELECT 1 FROM ProductBranch b
                           WHERE b.product.id = :productId AND b.branchId = :branchId
                             AND b.active = true)
            THEN true ELSE false END
            FROM Product p WHERE p.id = :productId
            """)
    Optional<Boolean> isSoldAt(@Param("productId") Long productId,
                               @Param("branchId") Long branchId);
}
