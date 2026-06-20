package com.erp.api;

import com.erp.modules.products.domain.dto.BarcodeSymbologyRuleDto;
import com.erp.modules.products.domain.dto.CreateBarcodeSymbologyRuleRequest;
import com.erp.modules.products.domain.dto.UpdateBarcodeSymbologyRuleRequest;
import com.erp.modules.products.service.BarcodeSymbologyRuleService;
import jakarta.validation.Valid;
import java.util.List;
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
 * Barcode symbology rule administration (ADR-0044 D-1a / BR-9).
 * Base path: {@code /api/v1/products/symbology-rules}.
 * Responses are auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 */
@RestController
@RequestMapping("/api/v1/products/symbology-rules")
public class BarcodeSymbologyController {

    private final BarcodeSymbologyRuleService service;

    public BarcodeSymbologyController(BarcodeSymbologyRuleService service) {
        this.service = service;
    }

    @GetMapping
    @PreAuthorize("@perm.has('PRODUCT.VIEW')")
    public List<BarcodeSymbologyRuleDto> list(@RequestParam Long companyId) {
        return service.listByCompany(companyId);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('PRODUCT.VIEW')")
    public BarcodeSymbologyRuleDto get(@PathVariable String uid) {
        return service.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.companyUid,'company','PRODUCT.SYMBOLOGY.MANAGE')")
    public BarcodeSymbologyRuleDto create(@Valid @RequestBody CreateBarcodeSymbologyRuleRequest request) {
        return service.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'symbologyrule','PRODUCT.SYMBOLOGY.MANAGE')")
    public BarcodeSymbologyRuleDto update(@PathVariable String uid,
                                          @Valid @RequestBody UpdateBarcodeSymbologyRuleRequest request) {
        return service.updateByUid(uid, request);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'symbologyrule','PRODUCT.SYMBOLOGY.MANAGE')")
    public void archive(@PathVariable String uid) {
        service.archiveByUid(uid);
    }
}
