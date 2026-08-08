package com.erp.api;

import com.erp.modules.stock.domain.dto.CreateStockTransferRequest;
import com.erp.modules.stock.domain.dto.StockTransferDto;
import com.erp.modules.stock.service.StockTransferService;
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
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Inter-location stock transfer REST surface (ADR-0028 D-5, FR-INVD-08..11).
 *
 * <p>Both transfer modes work between any two locations in the company, in the same branch or in
 * different branches. INSTANT is the one-sided mode — the sender completes it alone, so it is the
 * mode to use when the destination is a shop that is not on the system (no destination user, no
 * confirmation step). IN_TRANSIT is the two-sided mode where the destination confirms receipt.
 *
 * <p>Permission gates:
 * <ul>
 *   <li>STOCK.TRANSFER.VIEW — read</li>
 *   <li>STOCK.TRANSFER.CREATE — create / dispatch / instant-complete</li>
 *   <li>STOCK.TRANSFER.RECEIVE — receive</li>
 * </ul>
 *
 * <p>All responses are auto-wrapped in {@code ApiResponse<T>} by
 * {@link com.erp.platform.common.api.ApiResponseAdvice}.
 */
@RestController
@RequestMapping("/api/v1/stock-transfers")
public class StockTransferController {

    private final StockTransferService transferService;

    public StockTransferController(StockTransferService transferService) {
        this.transferService = transferService;
    }

    // -------------------------------------------------------------------------
    // Writes
    // -------------------------------------------------------------------------

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('STOCK.TRANSFER.CREATE')")
    public StockTransferDto create(@Valid @RequestBody CreateStockTransferRequest request) {
        return transferService.create(request);
    }

    /**
     * Complete an INSTANT transfer: DRAFT → COMPLETED in one TX (no in-transit leg, no confirmation
     * from the destination). Works across branches as well as within one — the destination branch
     * needs no user account, which is exactly what an off-system shop looks like.
     */
    @PatchMapping("/uid/{uid}/complete-instant")
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.CREATE')")
    public StockTransferDto completeInstant(@PathVariable String uid) {
        return transferService.completeInstant(uid);
    }

    /** Dispatch to in-transit: DRAFT → DISPATCHED, publishes STOCK.TRANSFER.DISPATCHED. */
    @PatchMapping("/uid/{uid}/dispatch")
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.CREATE')")
    public StockTransferDto dispatch(@PathVariable String uid) {
        return transferService.dispatch(uid);
    }

    /** Receive from in-transit: DISPATCHED → RECEIVED, publishes STOCK.TRANSFER.RECEIVED. */
    @PatchMapping("/uid/{uid}/receive")
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.RECEIVE')")
    public StockTransferDto receive(@PathVariable String uid) {
        return transferService.receive(uid);
    }

    @DeleteMapping("/uid/{uid}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.CREATE')")
    public void cancel(@PathVariable String uid) {
        transferService.cancel(uid);
    }

    // -------------------------------------------------------------------------
    // Reads
    // -------------------------------------------------------------------------

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid, 'stocktransfer', 'STOCK.TRANSFER.VIEW')")
    public StockTransferDto getByUid(@PathVariable String uid) {
        return transferService.getByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('STOCK.TRANSFER.VIEW')")
    public ApiResponse<List<StockTransferDto>> list(Pageable pageable) {
        Page<StockTransferDto> page = transferService.list(pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
