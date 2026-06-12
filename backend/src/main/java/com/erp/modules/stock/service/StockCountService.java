package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.CreateStockCountRequest;
import com.erp.modules.stock.domain.dto.EnterCountRequest;
import com.erp.modules.stock.domain.dto.StockCountDto;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Stock count / cycle count operations (ADR-0028 D-6, FR-INVD-12..17).
 */
public interface StockCountService {

    /** Create a DRAFT count for a location and snapshot system_qty per in-scope product. */
    StockCountDto create(CreateStockCountRequest request);

    /** Enter counted quantities for lines (COUNTING). */
    StockCountDto enterCount(String countUid, EnterCountRequest request);

    /**
     * Post the count: compute variance against live on-hand, post ADJUSTMENT movements,
     * accumulate net value and post one STOCK_ADJUSTMENT GL journal (BR-INVD-08, D-6).
     */
    StockCountDto post(String countUid, LocalDate postingDate);

    /** Cancel a DRAFT or COUNTING count. */
    StockCountDto cancel(String countUid);

    /** Get a count by uid. */
    StockCountDto getByUid(String countUid);

    /** Paged list for the caller's active branch. */
    Page<StockCountDto> list(Pageable pageable);
}
