package com.erp.api;

import com.erp.modules.stock.domain.dto.StockBatchDto;
import com.erp.modules.stock.service.StockBatchService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Stock batch / lot read surface (ADR-0028 D-7, FR-INVD-18..22).
 *
 * <p>Batch records are written internally on receipt; this controller exposes reads only.
 * Write-side operations (receiveQty, consumeFefo) are called by internal services.
 *
 * <p>Permission gate: {@code STOCK.VIEW}.
 */
@RestController
@RequestMapping("/api/v1/stock-batches")
public class StockBatchController {

    private final StockBatchService batchService;

    public StockBatchController(StockBatchService batchService) {
        this.batchService = batchService;
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'stockbatch', 'STOCK.VIEW')")
    public StockBatchDto getByUid(@PathVariable String uid) {
        return batchService.getByUid(uid);
    }

    /**
     * GET /api/v1/stock-batches?companyId=&locationId=&productId= — paged batch list at a location.
     */
    @GetMapping
    @PreAuthorize("@perm.has('STOCK.VIEW')")
    public ApiResponse<List<StockBatchDto>> listAtLocation(
            @RequestParam Long companyId,
            @RequestParam Long locationId,
            @RequestParam Long productId,
            Pageable pageable) {
        Page<StockBatchDto> page = batchService.listAtLocation(companyId, locationId, productId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /**
     * GET /api/v1/stock-batches/expiring?companyId=&horizon=YYYY-MM-DD — expiry report.
     * Returns batches expiring on or before the given horizon with qty > 0.
     */
    @GetMapping("/expiring")
    @PreAuthorize("@perm.has('INVENTORY.EXPIRY.VIEW')")
    public ApiResponse<List<StockBatchDto>> listExpiring(
            @RequestParam Long companyId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate horizon,
            Pageable pageable) {
        Page<StockBatchDto> page = batchService.listExpiring(companyId, horizon, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
