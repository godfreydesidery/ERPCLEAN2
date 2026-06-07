package com.erp.api;

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
import com.erp.modules.products.domain.dto.ProductPriceDto;
import com.erp.modules.products.domain.dto.SetProductPriceRequest;
import com.erp.modules.products.domain.dto.UpdateProductRequest;
import com.erp.modules.products.service.ProductService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Product master administration (FR-PROD-01, ADR-0007 D-10/D-11/D-12).
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Gates use {@code @perm.has} / {@code @perm.scoped} — NOT hasAuthority (brief §4.1).
 */
@RestController
@RequestMapping("/api/v1/products")
public class ProductController {

    private final ProductService productService;

    public ProductController(ProductService productService) {
        this.productService = productService;
    }

    // -------------------------------------------------------------------------
    // Core CRUD
    // -------------------------------------------------------------------------

    @GetMapping
    @PreAuthorize("@perm.has('PRODUCT.VIEW')")
    public ApiResponse<List<ProductDto>> list(@RequestParam Long companyId,
                                              @RequestParam(required = false) String q,
                                              Pageable pageable) {
        Page<ProductDto> page = productService.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('PRODUCT.VIEW')")
    public ProductDto get(@PathVariable String uid) {
        return productService.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.companyUid,'company','PRODUCT.MANAGE')")
    public ProductDto create(@Valid @RequestBody CreateProductRequest request) {
        return productService.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public ProductDto update(@PathVariable String uid,
                             @Valid @RequestBody UpdateProductRequest request) {
        return productService.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public void archive(@PathVariable String uid) {
        productService.archiveByUid(uid);
    }

    @PutMapping("/uid/{uid}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public void restore(@PathVariable String uid) {
        productService.restoreByUid(uid);
    }

    // -------------------------------------------------------------------------
    // Branches
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/branches")
    @PreAuthorize("@perm.has('PRODUCT.VIEW')")
    public List<ProductBranchDto> listBranches(@PathVariable String uid) {
        return productService.listBranches(uid);
    }

    @PostMapping("/uid/{uid}/branches")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.BRANCH.ASSIGN')")
    public ProductBranchDto assignBranch(@PathVariable String uid,
                                         @Valid @RequestBody AssignProductBranchRequest request) {
        return productService.assignBranch(uid, request);
    }

    @DeleteMapping("/uid/{uid}/branches/{branchUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.BRANCH.ASSIGN')")
    public void removeBranch(@PathVariable String uid, @PathVariable String branchUid) {
        productService.removeBranch(uid, branchUid);
    }

    // -------------------------------------------------------------------------
    // Bulk packs
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/bulk-packs")
    @PreAuthorize("@perm.has('PRODUCT.VIEW')")
    public List<ProductBulkPackDto> listBulkPacks(@PathVariable String uid) {
        return productService.listBulkPacks(uid);
    }

    @PostMapping("/uid/{uid}/bulk-packs")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public ProductBulkPackDto addBulkPack(@PathVariable String uid,
                                          @Valid @RequestBody CreateBulkPackRequest request) {
        return productService.addBulkPack(uid, request);
    }

    @DeleteMapping("/uid/{uid}/bulk-packs/{bulkPackUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public void removeBulkPack(@PathVariable String uid, @PathVariable String bulkPackUid) {
        productService.removeBulkPack(uid, bulkPackUid);
    }

    // -------------------------------------------------------------------------
    // Barcodes
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/barcodes")
    @PreAuthorize("@perm.has('PRODUCT.VIEW')")
    public List<ProductBarcodeDto> listBarcodes(@PathVariable String uid) {
        return productService.listBarcodes(uid);
    }

    @PostMapping("/uid/{uid}/barcodes")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public ProductBarcodeDto addBarcode(@PathVariable String uid,
                                        @Valid @RequestBody AddBarcodeRequest request) {
        return productService.addBarcode(uid, request);
    }

    @DeleteMapping("/uid/{uid}/barcodes/{barcodeUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public void removeBarcode(@PathVariable String uid, @PathVariable String barcodeUid) {
        productService.removeBarcode(uid, barcodeUid);
    }

    /**
     * POS barcode lookup — resolves a scannable barcode to its product within the active company
     * (NFR-PROD-01). Scoped by companyId to prevent cross-tenant leaks (brief §3.1 barcode rule).
     */
    @GetMapping("/barcode-lookup")
    @PreAuthorize("@perm.has('PRODUCT.VIEW')")
    public ProductBarcodeDto lookupBarcode(@RequestParam Long companyId,
                                           @RequestParam String barcode) {
        return productService.lookupBarcode(companyId, barcode);
    }

    // -------------------------------------------------------------------------
    // Prices
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/prices")
    @PreAuthorize("@perm.has('PRODUCT.VIEW')")
    public List<ProductPriceDto> listPrices(@PathVariable String uid) {
        return productService.listPrices(uid);
    }

    @PostMapping("/uid/{uid}/prices")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public ProductPriceDto setPrice(@PathVariable String uid,
                                    @Valid @RequestBody SetProductPriceRequest request) {
        return productService.setPrice(uid, request);
    }

    @DeleteMapping("/uid/{uid}/prices/{priceListUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public void removePrice(@PathVariable String uid, @PathVariable String priceListUid) {
        productService.removePrice(uid, priceListUid);
    }

    // -------------------------------------------------------------------------
    // Components
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}/components")
    @PreAuthorize("@perm.has('PRODUCT.VIEW')")
    public List<ProductComponentDto> listComponents(@PathVariable String uid) {
        return productService.listComponents(uid);
    }

    @PostMapping("/uid/{uid}/components")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public ProductComponentDto addComponent(@PathVariable String uid,
                                            @Valid @RequestBody AddComponentRequest request) {
        return productService.addComponent(uid, request);
    }

    @DeleteMapping("/uid/{uid}/components/{componentUid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'product','PRODUCT.MANAGE')")
    public void removeComponent(@PathVariable String uid, @PathVariable String componentUid) {
        productService.removeComponent(uid, componentUid);
    }
}
