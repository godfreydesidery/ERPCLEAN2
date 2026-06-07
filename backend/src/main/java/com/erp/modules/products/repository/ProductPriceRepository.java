package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.ProductPrice;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductPriceRepository extends JpaRepository<ProductPrice, Long> {

    List<ProductPrice> findByProductId(Long productId);

    Optional<ProductPrice> findByProductIdAndPriceListId(Long productId, Long priceListId);
}
