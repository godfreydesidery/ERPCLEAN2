package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.CreateStockTransferRequest;
import com.erp.modules.stock.domain.dto.StockTransferDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Inter-location stock transfer operations (ADR-0028 D-5, FR-INVD-08..11).
 *
 * <p>Neither mode is limited to a single branch. The difference is who closes the transfer:
 * INSTANT is one-sided (the sender posts both legs and the document is done), IN_TRANSIT is
 * two-sided (the destination confirms receipt). INSTANT is therefore the mode for sending stock to
 * a location whose branch has no users on the system at all — that is a supported setup, not a
 * workaround.
 */
public interface StockTransferService {

    /** Create a new DRAFT transfer. */
    StockTransferDto create(CreateStockTransferRequest request);

    /**
     * Complete an INSTANT transfer: posts TRANSFER_OUT at the source and TRANSFER_IN at the
     * destination in one TX, then completes the document (DRAFT → COMPLETED). No in-transit leg and
     * no confirmation from the destination — source and destination may be in different branches,
     * and the destination branch need not have a single user.
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
