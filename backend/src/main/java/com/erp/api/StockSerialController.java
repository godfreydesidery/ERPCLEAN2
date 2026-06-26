package com.erp.api;

import com.erp.modules.stock.domain.dto.StockSerialDto;
import com.erp.modules.stock.domain.enums.SerialStatus;
import com.erp.modules.stock.service.StockSerialService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock serial number read surface (ADR-0028 D-7, FR-INVD-23..27).
 *
 * <p>Write-side operations (record/issue/return/restock/move) are called by internal services.
 * This controller exposes reads only: lookup by uid, by serial number, by location, by product.
 *
 * <p>Permission gate: {@code STOCK.VIEW}.
 */
@RestController
@RequestMapping("/api/v1/stock-serials")
public class StockSerialController {

    private final StockSerialService serialService;

    public StockSerialController(StockSerialService serialService) {
        this.serialService = serialService;
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'stockserial', 'STOCK.VIEW')")
    public StockSerialDto getByUid(@PathVariable String uid) {
        return serialService.getByUid(uid);
    }

    /**
     * GET /api/v1/stock-serials/lookup?companyId=&productId=&serialNumber=
     * Look up a specific serial by serial number within a company-product scope.
     */
    @GetMapping("/lookup")
    @PreAuthorize("@perm.has('STOCK.VIEW')")
    public StockSerialDto lookup(@RequestParam Long companyId,
                                  @RequestParam Long productId,
                                  @RequestParam String serialNumber) {
        return serialService.lookup(companyId, productId, serialNumber);
    }

    /**
     * GET /api/v1/stock-serials?companyId=&locationId=&productId=[&status=IN_STOCK]
     * Paged list at a location for a product; status filter optional.
     */
    @GetMapping
    @PreAuthorize("@perm.has('STOCK.VIEW')")
    public ApiResponse<List<StockSerialDto>> listAtLocation(
            @RequestParam Long companyId,
            @RequestParam Long locationId,
            @RequestParam Long productId,
            @RequestParam(required = false) SerialStatus status,
            Pageable pageable) {
        Page<StockSerialDto> page =
                serialService.listAtLocation(companyId, locationId, productId, status, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /**
     * GET /api/v1/stock-serials/product/uid/{productUid}?companyId=
     * Full history paged list by product uid (FR-INVD-27).
     */
    @GetMapping("/product/uid/{productUid}")
    @PreAuthorize("@perm.has('STOCK.VIEW')")
    public ApiResponse<List<StockSerialDto>> listByProduct(
            @PathVariable String productUid,
            @RequestParam Long companyId,
            Pageable pageable) {
        Page<StockSerialDto> page = serialService.listByProductUid(companyId, productUid, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
