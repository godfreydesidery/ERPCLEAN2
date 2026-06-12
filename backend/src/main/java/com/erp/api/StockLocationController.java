package com.erp.api;

import com.erp.modules.stock.domain.dto.CreateStockLocationRequest;
import com.erp.modules.stock.domain.dto.StockLocationDto;
import com.erp.modules.stock.domain.dto.UpdateStockLocationRequest;
import com.erp.modules.stock.service.StockLocationService;
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
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock location master REST surface (ADR-0028 D-4, FR-INVD-01..04).
 *
 * <p>All responses are auto-wrapped in {@code ApiResponse<T>} by
 * {@link com.erp.platform.common.api.ApiResponseAdvice}.
 *
 * <p>Permission gates:
 * <ul>
 *   <li>STOCK.LOCATION.VIEW — read</li>
 *   <li>STOCK.LOCATION.MANAGE — create / update / deactivate / set-default</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/v1/stock-locations")
public class StockLocationController {

    private final StockLocationService locationService;

    public StockLocationController(StockLocationService locationService) {
        this.locationService = locationService;
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('STOCK.LOCATION.MANAGE')")
    public StockLocationDto create(@Valid @RequestBody CreateStockLocationRequest request) {
        return locationService.create(request);
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'stocklocation', 'STOCK.LOCATION.MANAGE')")
    public StockLocationDto update(@PathVariable String uid,
                                    @Valid @RequestBody UpdateStockLocationRequest request) {
        return locationService.update(uid, request);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid, 'stocklocation', 'STOCK.LOCATION.MANAGE')")
    public void deactivate(@PathVariable String uid) {
        locationService.deactivate(uid);
    }

    @PatchMapping("/uid/{uid}/reactivate")
    @PreAuthorize("@perm.scoped(#uid, 'stocklocation', 'STOCK.LOCATION.MANAGE')")
    public StockLocationDto reactivate(@PathVariable String uid) {
        locationService.reactivate(uid);
        return locationService.getByUid(uid);
    }

    @PatchMapping("/uid/{uid}/default")
    @PreAuthorize("@perm.scoped(#uid, 'stocklocation', 'STOCK.LOCATION.MANAGE')")
    public StockLocationDto setDefault(@PathVariable String uid) {
        return locationService.setDefault(uid);
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'stocklocation', 'STOCK.LOCATION.VIEW')")
    public StockLocationDto getByUid(@PathVariable String uid) {
        return locationService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('STOCK.LOCATION.VIEW')")
    public ApiResponse<List<StockLocationDto>> list(Pageable pageable) {
        Page<StockLocationDto> page = locationService.listForBranch(pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @GetMapping("/active")
    @PreAuthorize("@perm.has('STOCK.LOCATION.VIEW')")
    public List<StockLocationDto> activeForBranch(String branchUid) {
        return locationService.activeForBranch(branchUid);
    }
}
