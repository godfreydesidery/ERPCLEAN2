package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.ProductComponent;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductComponentRepository extends JpaRepository<ProductComponent, Long> {

    List<ProductComponent> findByComposedProductId(Long composedProductId);

    Optional<ProductComponent> findByComposedProductIdAndComponentProductId(
            Long composedProductId, Long componentProductId);
}
