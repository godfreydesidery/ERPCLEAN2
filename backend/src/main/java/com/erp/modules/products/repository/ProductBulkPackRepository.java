package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.ProductBulkPack;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductBulkPackRepository extends JpaRepository<ProductBulkPack, Long> {

    Optional<ProductBulkPack> findByUid(String uid);

    List<ProductBulkPack> findByProductId(Long productId);
}
