package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.ProductBulkPack;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductBulkPackRepository extends JpaRepository<ProductBulkPack, Long> {

    Optional<ProductBulkPack> findByUid(String uid);

    /**
     * Ownership-scoped lookup: resolves a bulk pack only when it belongs to the given product.
     * Used by remove so a child cannot be deleted via a different product's URL (SR finding 2).
     */
    Optional<ProductBulkPack> findByUidAndProductId(String uid, Long productId);

    List<ProductBulkPack> findByProductId(Long productId);
}
