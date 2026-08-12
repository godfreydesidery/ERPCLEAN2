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

    /**
     * Duplicate probe for add: {@code (product_id, unit_id)} is UNIQUE, so hitting the DB first
     * lets the service name the unit and its current size instead of surfacing the generic
     * "record with the same unique identifier" 409.
     */
    Optional<ProductBulkPack> findByProductIdAndUnitId(Long productId, Long unitId);

    List<ProductBulkPack> findByProductId(Long productId);
}
