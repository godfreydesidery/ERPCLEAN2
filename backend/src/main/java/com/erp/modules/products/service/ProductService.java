package com.erp.modules.products.service;

import com.erp.modules.products.domain.dto.AddBarcodeRequest;
import com.erp.modules.products.domain.dto.AddComponentRequest;
import com.erp.modules.products.domain.dto.AssignProductBranchRequest;
import com.erp.modules.products.domain.dto.CreateBulkPackRequest;
import com.erp.modules.products.domain.dto.CreateProductRequest;
import com.erp.modules.products.domain.dto.ProductBarcodeDto;
import com.erp.modules.products.domain.dto.ProductBranchDto;
import com.erp.modules.products.domain.dto.ProductBulkPackDto;
import com.erp.modules.products.domain.dto.ProductComponentDto;
import com.erp.modules.products.domain.dto.ProductDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.SetProductWeighingRequest;
import com.erp.modules.products.domain.dto.ProductPriceDto;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.dto.UpdateProductRequest;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductService {

    ProductDto create(CreateProductRequest req);

    ProductDto getByUid(String uid);

    /**
     * Internal lookup by numeric id — for cross-module callers (e.g. stock-count snapshot)
     * that hold a productId and need code/name for denormalisation.
     * No scope check; caller is responsible for asserting company scope.
     */
    ProductDto getById(Long id);

    Page<ProductDto> list(Long companyId, String q, Pageable pageable);

    ProductDto updateByUid(String uid, UpdateProductRequest req);

    void archiveByUid(String uid);

    void restoreByUid(String uid);

    // --- Branches ---
    ProductBranchDto assignBranch(String uid, AssignProductBranchRequest req);

    void removeBranch(String uid, String branchUid);

    List<ProductBranchDto> listBranches(String uid);

    // --- Bulk packs ---
    ProductBulkPackDto addBulkPack(String uid, CreateBulkPackRequest req);

    void removeBulkPack(String productUid, String bulkPackUid);

    List<ProductBulkPackDto> listBulkPacks(String uid);

    // --- Barcodes ---
    ProductBarcodeDto addBarcode(String uid, AddBarcodeRequest req);

    void removeBarcode(String productUid, String barcodeUid);

    List<ProductBarcodeDto> listBarcodes(String uid);

    ProductBarcodeDto lookupBarcode(Long companyId, String barcode);

    // --- Prices ---
    ProductPriceDto setPrice(String uid, SetProductPriceRequest req);

    /**
     * Removes a price row. {@code unitUid} null/blank targets the base-unit row (unit_id IS NULL,
     * back-compatible with pre-ADR-0048 callers); a supplied uid targets that unit's per-unit row
     * (coerced to the base row when the uid resolves to the product's own base unit).
     */
    void removePrice(String productUid, String priceListUid, String unitUid);

    List<ProductPriceDto> listPrices(String uid);

    /**
     * Configures the product's weighed-goods (sell-by-weight) profile (ADR-0044 D-1b). When
     * {@code weighed=true} the product's base unit must be a WEIGHT unit, else the call is rejected.
     */
    ProductDto setWeighing(String uid, SetProductWeighingRequest req);

    // --- Valid transaction units ---

    /**
     * Returns the ordered list of units valid for transaction lines against this product:
     * the product's base unit first, then each configured ProductBulkPack unit (de-duplicated).
     * Used by the /uid/{uid}/units endpoint to drive unit-of-measure pickers in line editors.
     */
    List<UnitOfMeasureDto> listProductUnits(String productUid);

    // --- Components ---
    ProductComponentDto addComponent(String uid, AddComponentRequest req);

    void removeComponent(String composedUid, String componentUid);

    List<ProductComponentDto> listComponents(String uid);
}
