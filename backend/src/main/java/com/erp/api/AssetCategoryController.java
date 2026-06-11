package com.erp.api;

import com.erp.modules.fixedassets.domain.dto.AssetCategoryDto;
import com.erp.modules.fixedassets.domain.dto.CreateAssetCategoryRequest;
import com.erp.modules.fixedassets.domain.dto.UpdateAssetCategoryRequest;
import com.erp.modules.fixedassets.service.AssetCategoryService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
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
 * Asset category REST controller (ADR-0030 D-15).
 * URL base: /api/v1/fixed-assets/categories
 */
@RestController
@RequestMapping("/api/v1/fixed-assets/categories")
public class AssetCategoryController {

    private final AssetCategoryService categoryService;
    private final ScopeGuard           scopeGuard;

    public AssetCategoryController(AssetCategoryService categoryService, ScopeGuard scopeGuard) {
        this.categoryService = categoryService;
        this.scopeGuard      = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('FA.CATEGORY.VIEW')")
    public ApiResponse<List<AssetCategoryDto>> list(@RequestParam Long companyId) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return ApiResponse.ok(categoryService.listByCompany(companyId));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('FA.CATEGORY.MANAGE')")
    public AssetCategoryDto create(@Valid @RequestBody CreateAssetCategoryRequest req) {
        return categoryService.create(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'assetcategory','FA.CATEGORY.VIEW')")
    public AssetCategoryDto get(@PathVariable String uid) {
        return categoryService.getByUid(uid);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'assetcategory','FA.CATEGORY.MANAGE')")
    public AssetCategoryDto update(@PathVariable String uid,
                                    @Valid @RequestBody UpdateAssetCategoryRequest req) {
        return categoryService.update(uid, req);
    }

    @DeleteMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'assetcategory','FA.CATEGORY.MANAGE')")
    public AssetCategoryDto archive(@PathVariable String uid) {
        return categoryService.archive(uid);
    }
}
