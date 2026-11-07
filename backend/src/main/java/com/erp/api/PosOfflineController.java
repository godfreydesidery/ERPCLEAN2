package com.erp.api;

import com.erp.modules.sales.domain.dto.AcknowledgeOfflineReviewRequest;
import com.erp.modules.sales.domain.dto.OfflineSyncRequest;
import com.erp.modules.sales.domain.dto.OfflineSyncResultDto;
import com.erp.modules.sales.domain.dto.PosOfflinePeriodDto;
import com.erp.modules.sales.domain.dto.PosOfflineReviewDto;
import com.erp.modules.sales.domain.enums.OfflineReviewStatus;
import com.erp.modules.sales.service.PosOfflineSyncService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Offline POS (ADR-0068): a till's sync after selling offline, and the manager's review list of
 * what the tills sold offline.
 */
@RestController
@RequestMapping("/api/v1/pos")
public class PosOfflineController {

    private final PosOfflineSyncService offline;

    public PosOfflineController(PosOfflineSyncService offline) {
        this.offline = offline;
    }

    /**
     * A till uploads what it did offline: shifts it opened, sales it rang, its offline periods.
     * Whoever may ring a sale may send the till's offline sales. Safe to repeat — every item carries
     * the till's own key.
     */
    @PostMapping("/offline-sync")
    @PreAuthorize("@perm.has('POS.SALE.CREATE')")
    public OfflineSyncResultDto sync(@Valid @RequestBody OfflineSyncRequest request) {
        return offline.sync(request);
    }

    /** Offline review items of the active branch (OPEN by default). */
    @GetMapping("/offline-reviews")
    @PreAuthorize("@perm.has('POS.OFFLINE.REVIEW')")
    public ApiResponse<List<PosOfflineReviewDto>> listReviews(
            @RequestParam(required = false) OfflineReviewStatus status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PosOfflineReviewDto> result = offline.listReviews(status, page, size);
        return ApiResponse.ok(result.getContent(), PageMeta.from(result));
    }

    /** A manager acknowledges one review item (scoped to the item's company and branch in the service). */
    @PostMapping("/offline-reviews/uid/{uid}/acknowledge")
    @PreAuthorize("@perm.has('POS.OFFLINE.REVIEW')")
    public PosOfflineReviewDto acknowledge(@PathVariable String uid,
                                           @Valid @RequestBody(required = false)
                                           AcknowledgeOfflineReviewRequest request) {
        return offline.acknowledge(uid, request);
    }

    /** Offline periods of the active branch: who switched, why, how long, how many sales. */
    @GetMapping("/offline-periods")
    @PreAuthorize("@perm.has('POS.OFFLINE.REVIEW')")
    public ApiResponse<List<PosOfflinePeriodDto>> listPeriods(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        Page<PosOfflinePeriodDto> result = offline.listPeriods(page, size);
        return ApiResponse.ok(result.getContent(), PageMeta.from(result));
    }
}
