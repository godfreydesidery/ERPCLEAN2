package com.erp.api;

import com.erp.modules.costing.domain.dto.CreateDimensionValueRequest;
import com.erp.modules.costing.domain.dto.DimensionValueDto;
import com.erp.modules.costing.domain.dto.UpdateDimensionValueRequest;
import com.erp.modules.costing.service.DimensionService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dimension value (cost centre / department) CRUD (ADR-0025 D-1, FR-CC-02..05).
 * COSTING.MANAGE for writes; COSTING.VIEW for reads.
 */
@RestController
@RequestMapping("/api/v1/dimension-values")
public class DimensionValueController {

    private final DimensionService service;

    public DimensionValueController(DimensionService service) {
        this.service = service;
    }

    /** Create a new cost centre / department value. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('COSTING.MANAGE')")
    public ApiResponse<DimensionValueDto> create(@RequestBody CreateDimensionValueRequest req) {
        return ApiResponse.ok(service.createValue(req));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'dimensionvalue','COSTING.VIEW')")
    public ApiResponse<DimensionValueDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(service.getValueByUid(uid));
    }

    /** List all values for a dimension (paged). */
    @GetMapping
    @PreAuthorize("@perm.has('COSTING.VIEW')")
    public ApiResponse<List<DimensionValueDto>> list(@RequestParam String dimensionUid,
                                                      Pageable pageable) {
        Page<DimensionValueDto> page = service.listValues(dimensionUid, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')")
    public ApiResponse<DimensionValueDto> update(@PathVariable String uid,
                                                  @RequestBody UpdateDimensionValueRequest req) {
        return ApiResponse.ok(service.updateValue(uid, req));
    }

    /** Deactivate a value — excludes from new tagging (FR-CC-04). */
    @PatchMapping("/uid/{uid}/deactivate")
    @PreAuthorize("@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')")
    public ApiResponse<DimensionValueDto> deactivate(@PathVariable String uid) {
        return ApiResponse.ok(service.deactivateValue(uid));
    }

    /** Reactivate a previously deactivated value. */
    @PatchMapping("/uid/{uid}/activate")
    @PreAuthorize("@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')")
    public ApiResponse<DimensionValueDto> activate(@PathVariable String uid) {
        return ApiResponse.ok(service.activateValue(uid));
    }

    /**
     * Hard-delete a value — rejected if the value has any postings (FR-CC-03, BR-CC-05).
     * Use /deactivate when postings exist.
     */
    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid,'dimensionvalue','COSTING.MANAGE')")
    public void delete(@PathVariable String uid) {
        service.deleteValue(uid);
    }
}
