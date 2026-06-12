package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.CreateStockTransferRequest;
import com.erp.modules.stock.domain.dto.StockTransferDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Inter-location stock transfer operations (ADR-0028 D-5, FR-INVD-08..11).
 */
public interface StockTransferService {

    /** Create a new DRAFT transfer. */
    StockTransferDto create(CreateStockTransferRequest request);

    /**
     * Dispatch an INSTANT transfer (same-branch): posts TRANSFER_OUT at source + TRANSFER_IN at
     * dest in one TX, completes the document (DRAFT → COMPLETED).
     */
    StockTransferDto completeInstant(String transferUid);

    /**
     * Dispatch an IN_TRANSIT transfer: posts TRANSFER_OUT at source → in-transit.
     * Publishes STOCK.TRANSFER.DISPATCHED to the outbox.
     */
    StockTransferDto dispatch(String transferUid);

    /**
     * Receive an IN_TRANSIT transfer: posts TRANSFER_IN from in-transit → dest.
     * Publishes STOCK.TRANSFER.RECEIVED to the outbox.
     */
    StockTransferDto receive(String transferUid);

    /** Cancel a DRAFT transfer. */
    StockTransferDto cancel(String transferUid);

    /** Get a single transfer by uid. */
    StockTransferDto getByUid(String transferUid);

    /** Paged list of transfers for the caller's company. */
    Page<StockTransferDto> list(Pageable pageable);
}
