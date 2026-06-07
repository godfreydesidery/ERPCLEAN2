package com.erp.api;

import com.erp.modules.products.domain.dto.CreateUnitOfMeasureRequest;
import com.erp.modules.products.domain.dto.UnitOfMeasureDto;
import com.erp.modules.products.domain.dto.UpdateUnitOfMeasureRequest;
import com.erp.modules.products.service.UnitOfMeasureService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
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
 * UnitOfMeasure master administration (brief §UoM master).
 * Responses auto-wrapped in {@code ApiResponse<T>} by {@code ApiResponseAdvice}.
 * Gates mirror PriceListController: @perm.has / @perm.scoped.
 */
@RestController
@RequestMapping("/api/v1/units")
public class UnitOfMeasureController {

    private final UnitOfMeasureService unitService;

    public UnitOfMeasureController(UnitOfMeasureService unitService) {
        this.unitService = unitService;
    }

    @GetMapping
    @PreAuthorize("@perm.has('UOM.VIEW')")
    public ApiResponse<List<UnitOfMeasureDto>> list(@RequestParam Long companyId,
                                                    @RequestParam(required = false) String q,
                                                    Pageable pageable) {
        Page<UnitOfMeasureDto> page = unitService.list(companyId, q, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.has('UOM.VIEW')")
    public UnitOfMeasureDto get(@PathVariable String uid) {
        return unitService.getByUid(uid);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.scoped(#request.companyUid,'company','UOM.MANAGE')")
    public UnitOfMeasureDto create(@Valid @RequestBody CreateUnitOfMeasureRequest request) {
        return unitService.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'unit','UOM.MANAGE')")
    public UnitOfMeasureDto update(@PathVariable String uid,
                                   @Valid @RequestBody UpdateUnitOfMeasureRequest request) {
        return unitService.updateByUid(uid, request);
    }

    @PutMapping("/uid/{uid}/archive")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'unit','UOM.MANAGE')")
    public void archive(@PathVariable String uid) {
        unitService.archiveByUid(uid);
    }

    @PutMapping("/uid/{uid}/restore")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'unit','UOM.MANAGE')")
    public void restore(@PathVariable String uid) {
        unitService.restoreByUid(uid);
    }
}
