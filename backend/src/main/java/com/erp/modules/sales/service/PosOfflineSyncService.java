package com.erp.modules.sales.service;

import com.erp.modules.sales.domain.dto.AcknowledgeOfflineReviewRequest;
import com.erp.modules.sales.domain.dto.OfflineSyncRequest;
import com.erp.modules.sales.domain.dto.OfflineSyncResultDto;
import com.erp.modules.sales.domain.dto.PosOfflineReviewDto;
import com.erp.modules.sales.domain.dto.PosOfflinePeriodDto;
import com.erp.modules.sales.domain.enums.OfflineReviewStatus;
import org.springframework.data.domain.Page;

/** Offline POS (ADR-0068): the till's sync, and the manager's review list. */
public interface PosOfflineSyncService {

    /**
     * Takes a till's offline batch: shifts it opened offline, the sales it rang, its offline periods.
     * Each sale is posted (or held) on its own, so the answer can mix outcomes. Safe to repeat.
     */
    OfflineSyncResultDto sync(OfflineSyncRequest request);

    /** Review items of the caller's active branch, newest first. */
    Page<PosOfflineReviewDto> listReviews(OfflineReviewStatus status, int page, int size);

    /** A manager acknowledges one review item. */
    PosOfflineReviewDto acknowledge(String reviewUid, AcknowledgeOfflineReviewRequest request);

    /** Offline periods of the caller's active branch, newest first. */
    Page<PosOfflinePeriodDto> listPeriods(int page, int size);
}
