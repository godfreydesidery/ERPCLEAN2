package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.AdjustStockRequest;
import com.erp.modules.stock.domain.dto.OpeningBalanceRequest;
import com.erp.modules.stock.domain.dto.SetReorderLevelRequest;
import com.erp.modules.stock.domain.dto.StockMovementDto;
import com.erp.modules.stock.domain.dto.StockOnHandDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Manual stock operations and reads (ADR-0010 D-11).
 *
 * <p>Event-driven movements (GOODS_RECEIPT, SALE_ISSUE, SALE_REVERSAL, GOODS_RECEIPT_REVERSAL) are
 * NOT exposed here — they are posted by the system event consumers (D-5). There is no REST
 * endpoint for them (FR-STOCK-15).
 */
public interface StockService {

    /**
     * Record a manual ADJUSTMENT (+/-) for a stockable product at the caller's active branch.
     * reason is mandatory (BR-STOCK-05). Gate: STOCK.ADJUST.
     */
    StockMovementDto adjust(AdjustStockRequest request);

    /**
     * Seed an OPENING_BALANCE for a never-tracked (product, active branch).
     * Rejected if any prior movement exists (D-11). Gate: STOCK.OPENING.
     */
    StockMovementDto openingBalance(OpeningBalanceRequest request);

    /**
     * Set or clear the optional reorder-level indicator on an on-hand row addressed by uid.
     * Gate: STOCK.ADJUST.
     */
    StockOnHandDto setReorderLevel(String uid, SetReorderLevelRequest request);

    /**
     * Paged on-hand list for the caller's active branch. Results carry derived negative/low flags.
     * Gate: STOCK.VIEW.
     */
    Page<StockOnHandDto> listOnHand(Pageable pageable);

    /**
     * Paged movement ledger for a product (by uid) at the caller's active branch (FR-STOCK-11).
     * Gate: STOCK.VIEW.
     */
    Page<StockMovementDto> listMovements(String productUid, Pageable pageable);
}
