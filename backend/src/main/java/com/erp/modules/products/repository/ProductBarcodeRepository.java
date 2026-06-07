package com.erp.modules.products.repository;

import com.erp.modules.products.domain.entity.ProductBarcode;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProductBarcodeRepository extends JpaRepository<ProductBarcode, Long> {

    Optional<ProductBarcode> findByUid(String uid);

    /**
     * Ownership-scoped lookup: resolves a barcode only when it belongs to the given product.
     * Used by remove so a child cannot be deleted via a different product's URL (SR finding 2).
     */
    Optional<ProductBarcode> findByUidAndProductId(String uid, Long productId);

    List<ProductBarcode> findByProductId(Long productId);

    /**
     * POS barcode scan — resolves a barcode value within a company to the product barcode row
     * (NFR-PROD-01). Uses the uq_product_barcode_company_value unique constraint as the lookup
     * index; single probe.
     */
    Optional<ProductBarcode> findByCompanyIdAndBarcode(Long companyId, String barcode);
}
