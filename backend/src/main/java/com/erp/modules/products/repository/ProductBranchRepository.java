package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.ProductBranch;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductBranchRepository extends JpaRepository<ProductBranch, Long> {

    List<ProductBranch> findByProductId(Long productId);

    Optional<ProductBranch> findByProductIdAndBranchId(Long productId, Long branchId);

    void deleteByProductIdAndBranchId(Long productId, Long branchId);
}
