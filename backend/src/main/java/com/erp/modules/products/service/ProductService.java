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
import com.erp.modules.products.domain.dto.ProductPriceDto;
import com.erp.modules.products.domain.dto.UpdateProductRequest;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface ProductService {

    ProductDto create(CreateProductRequest req);

    ProductDto getByUid(String uid);

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

    void removePrice(String productUid, String priceListUid);

    List<ProductPriceDto> listPrices(String uid);

    // --- Components ---
    ProductComponentDto addComponent(String uid, AddComponentRequest req);

    void removeComponent(String composedUid, String componentUid);

    List<ProductComponentDto> listComponents(String uid);
}
