package com.erp.api;

import com.erp.modules.stock.domain.dto.CreateStockCountRequest;
import com.erp.modules.stock.domain.dto.EnterCountRequest;
import com.erp.modules.stock.domain.dto.StockCountDto;
import com.erp.modules.stock.service.StockCountService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
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
 * Stock count / physical inventory REST surface (ADR-0028 D-6, FR-INVD-12..17).
 *
 * <p>Permission gates:
 * <ul>
 *   <li>STOCK.COUNT.VIEW — read</li>
 *   <li>STOCK.COUNT.CREATE — create, enter-count</li>
 *   <li>STOCK.COUNT.POST — post (triggers GL journal)</li>
 * </ul>
 *
 * <p>All responses are auto-wrapped in {@code ApiResponse<T>} by
 * {@link com.erp.platform.common.api.ApiResponseAdvice}.
 */
@RestController
@RequestMapping("/api/v1/stock-counts")
public class StockCountController {

    private final StockCountService countService;

    public StockCountController(StockCountService countService) {
        this.countService = countService;
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    /** Create a DRAFT count, snapshot system_qty, and freeze to COUNTING. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('STOCK.COUNT.CREATE')")
    public StockCountDto create(@Valid @RequestBody CreateStockCountRequest request) {
        return countService.create(request);
    }

    /** Enter counted quantities for lines (COUNTING phase). */
    @PatchMapping("/uid/{uid}/enter")
    @PreAuthorize("@perm.scoped(#uid, 'stockcount', 'STOCK.COUNT.CREATE')")
    public StockCountDto enterCount(@PathVariable String uid,
                                     @Valid @RequestBody EnterCountRequest request) {
        return countService.enterCount(uid, request);
    }

    /** Post the count: compute variance, post ADJUSTMENT movements, post one GL journal. */
    @PatchMapping("/uid/{uid}/post")
    @PreAuthorize("@perm.scoped(#uid, 'stockcount', 'STOCK.COUNT.POST')")
    public StockCountDto post(@PathVariable String uid,
                               @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                               LocalDate postingDate) {
        return countService.post(uid, postingDate);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid, 'stockcount', 'STOCK.COUNT.CREATE')")
    public void cancel(@PathVariable String uid) {
        countService.cancel(uid);
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'stockcount', 'STOCK.COUNT.VIEW')")
    public StockCountDto getByUid(@PathVariable String uid) {
        return countService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('STOCK.COUNT.VIEW')")
    public ApiResponse<List<StockCountDto>> list(Pageable pageable) {
        Page<StockCountDto> page = countService.list(pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
