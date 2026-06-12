package com.erp.api;

import com.erp.modules.fixedassets.domain.dto.AcquireFromBillRequest;
import com.erp.modules.fixedassets.domain.dto.AssetDisposalDto;
import com.erp.modules.fixedassets.domain.dto.AssetRevaluationDto;
import com.erp.modules.fixedassets.domain.dto.DepreciationScheduleLineDto;
import com.erp.modules.fixedassets.domain.dto.DisposeAssetRequest;
import com.erp.modules.fixedassets.domain.dto.FixedAssetDto;
import com.erp.modules.fixedassets.domain.dto.FixedAssetReconciliationDto;
import com.erp.modules.fixedassets.domain.dto.PlaceInServiceRequest;
import com.erp.modules.fixedassets.domain.dto.RegisterAssetRequest;
import com.erp.modules.fixedassets.domain.dto.RevalueAssetRequest;
import com.erp.modules.fixedassets.domain.dto.TransferAssetRequest;
import com.erp.modules.fixedassets.domain.dto.UpdateAssetRequest;
import com.erp.modules.fixedassets.domain.dto.WriteOffAssetRequest;
import com.erp.modules.fixedassets.domain.enums.FixedAssetStatus;
import com.erp.modules.fixedassets.service.AssetDisposalService;
import com.erp.modules.fixedassets.service.AssetRevaluationService;
import com.erp.modules.fixedassets.service.DepreciationScheduleService;
import com.erp.modules.fixedassets.service.FixedAssetReconQuery;
import com.erp.modules.fixedassets.service.FixedAssetService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
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
 * Fixed asset register REST controller (ADR-0030 D-15).
 * URL base: /api/v1/fixed-assets
 */
@RestController
@RequestMapping("/api/v1/fixed-assets")
public class FixedAssetController {

    private final FixedAssetService          assetService;
    private final AssetDisposalService       disposalService;
    private final AssetRevaluationService    revaluationService;
    private final DepreciationScheduleService scheduleService;
    private final FixedAssetReconQuery       reconQuery;
    private final ScopeGuard                 scopeGuard;

    public FixedAssetController(FixedAssetService assetService,
                                  AssetDisposalService disposalService,
                                  AssetRevaluationService revaluationService,
                                  DepreciationScheduleService scheduleService,
                                  FixedAssetReconQuery reconQuery,
                                  ScopeGuard scopeGuard) {
        this.assetService       = assetService;
        this.disposalService    = disposalService;
        this.revaluationService = revaluationService;
        this.scheduleService    = scheduleService;
        this.reconQuery         = reconQuery;
        this.scopeGuard         = scopeGuard;
    }

    @GetMapping
    @PreAuthorize("@perm.has('FA.VIEW')")
    public ApiResponse<List<FixedAssetDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) FixedAssetStatus status,
            Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Page<FixedAssetDto> page = assetService.listByCompany(companyId, status, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('FA.REGISTER.MANAGE')")
    public FixedAssetDto register(@Valid @RequestBody RegisterAssetRequest req) {
        return assetService.register(req);
    }

    @PostMapping("/acquire-from-bill")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('FA.REGISTER.MANAGE')")
    public FixedAssetDto acquireFromBill(@Valid @RequestBody AcquireFromBillRequest req) {
        return assetService.acquireFromBill(req);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'fixedasset','FA.VIEW')")
    public FixedAssetDto get(@PathVariable String uid) {
        return assetService.getByUid(uid);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'fixedasset','FA.REGISTER.MANAGE')")
    public FixedAssetDto update(@PathVariable String uid,
                                 @Valid @RequestBody UpdateAssetRequest req) {
        return assetService.update(uid, req);
    }

    @PostMapping("/uid/{uid}/place-in-service")
    @PreAuthorize("@perm.scoped(#uid,'fixedasset','FA.REGISTER.MANAGE')")
    public FixedAssetDto placeInService(@PathVariable String uid,
                                         @Valid @RequestBody PlaceInServiceRequest req) {
        return assetService.placeInService(uid, req);
    }

    @PostMapping("/uid/{uid}/transfer")
    @PreAuthorize("@perm.scoped(#uid,'fixedasset','FA.REGISTER.MANAGE')")
    public FixedAssetDto transfer(@PathVariable String uid,
                                   @Valid @RequestBody TransferAssetRequest req) {
        return assetService.transfer(uid, req);
    }

    @PostMapping("/uid/{uid}/dispose")
    @PreAuthorize("@perm.scoped(#uid,'fixedasset','FA.DISPOSE')")
    public AssetDisposalDto dispose(@PathVariable String uid,
                                     @Valid @RequestBody DisposeAssetRequest req) {
        return disposalService.dispose(uid, req);
    }

    @PostMapping("/uid/{uid}/write-off")
    @PreAuthorize("@perm.scoped(#uid,'fixedasset','FA.DISPOSE')")
    public AssetDisposalDto writeOff(@PathVariable String uid,
                                      @Valid @RequestBody WriteOffAssetRequest req) {
        return disposalService.writeOff(uid, req);
    }

    @PostMapping("/uid/{uid}/revalue")
    @PreAuthorize("@perm.scoped(#uid,'fixedasset','FA.DISPOSE')")
    public AssetRevaluationDto revalue(@PathVariable String uid,
                                        @Valid @RequestBody RevalueAssetRequest req) {
        return revaluationService.revalue(uid, req);
    }

    @GetMapping("/uid/{uid}/schedule")
    @PreAuthorize("@perm.scoped(#uid,'fixedasset','FA.VIEW')")
    public List<DepreciationScheduleLineDto> schedule(@PathVariable String uid) {
        return scheduleService.listByAsset(uid);
    }

    @GetMapping("/uid/{uid}/revaluations")
    @PreAuthorize("@perm.scoped(#uid,'fixedasset','FA.VIEW')")
    public List<AssetRevaluationDto> revaluations(@PathVariable String uid) {
        return revaluationService.listByAsset(uid);
    }

    @GetMapping("/reconciliation")
    @PreAuthorize("@perm.has('FA.VIEW')")
    public FixedAssetReconciliationDto reconciliation(@RequestParam Long companyId) {
        return reconQuery.reconcile(companyId);
    }
}
