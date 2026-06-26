package com.erp.api;

import com.erp.modules.costing.domain.dto.CreateDimensionRequest;
import com.erp.modules.costing.domain.dto.DimensionDto;
import com.erp.modules.costing.domain.dto.SetDimensionMandatoryRequest;
import com.erp.modules.costing.service.DimensionService;
import com.erp.platform.common.api.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dimension type REST controller (ADR-0025 D-1/D-10, FR-CC-01/13).
 * Built-in types (COST_CENTRE, DEPARTMENT) are seeder-owned; custom types may be created
 * for the two spare slots (DIMENSION_3, DIMENSION_4) via the POST endpoint.
 */
@RestController
@RequestMapping("/api/v1/dimensions")
public class DimensionController {

    private final DimensionService service;

    public DimensionController(DimensionService service) {
        this.service = service;
    }

    /**
     * Create a custom dimension type claiming the first free spare slot (DIMENSION_3 then
     * DIMENSION_4). At most two custom dimensions per company.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('COSTING.MANAGE')")
    public ApiResponse<DimensionDto> create(@Valid @RequestBody CreateDimensionRequest req) {
        return ApiResponse.ok(service.createDimension(req));
    }

    /** List all dimension types for a company (e.g. Cost Centre, Department). */
    @GetMapping
    @PreAuthorize("@perm.has('COSTING.VIEW')")
    public ApiResponse<List<DimensionDto>> list(@RequestParam Long companyId) {
        return ApiResponse.ok(service.listDimensions(companyId));
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'dimension','COSTING.VIEW')")
    public ApiResponse<DimensionDto> getByUid(@PathVariable String uid) {
        return ApiResponse.ok(service.getDimensionByUid(uid));
    }

    /**
     * Toggle mandatory/optional for a dimension type (FR-CC-13, COSTING.MANAGE).
     * When mandatory is true every posting must carry a value for this slot.
     */
    @PatchMapping("/uid/{uid}/mandatory")
    @PreAuthorize("@perm.scoped(#uid,'dimension','COSTING.MANAGE')")
    public ApiResponse<DimensionDto> setMandatory(@PathVariable String uid,
                                                   @RequestBody SetDimensionMandatoryRequest req) {
        return ApiResponse.ok(service.setMandatory(uid, req));
    }
}
