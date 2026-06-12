package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.StockBatchDto;
import java.math.BigDecimal;
import java.time.LocalDate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * Lot/batch tracking operations (ADR-0028 D-7, FR-INVD-18..22).
 *
 * <p>Batch records are created on goods receipt and decremented by FEFO on issue.
 * This service does NOT drive stock movements — the upstream receipt/issue services do.
 * It only maintains the {@code stock_batches} integrity invariant (BR-INVD-10).
 */
public interface StockBatchService {

    /**
     * Record a lot receipt: find-or-create the batch row and apply the positive quantity delta.
     * Called by the GoodsReceipt handler after the primary stock movement.
     *
     * @param companyId       tenant
     * @param branchId        branch
     * @param locationId      receiving location
     * @param productId       product
     * @param lotNumber       lot/batch number (NOT NULL for lot-tracked products)
     * @param manufactureDate optional manufacture date
     * @param expiryDate      optional expiry date (used for FEFO ordering)
     * @param qty             positive received quantity
     * @param actorId         user performing the action
     * @return DTO of the updated/created batch row
     */
    StockBatchDto receiveQty(Long companyId, Long branchId, Long locationId, Long productId,
                              String lotNumber, LocalDate manufactureDate, LocalDate expiryDate,
                              BigDecimal qty, Long actorId);

    /**
     * Consume quantity using FEFO: deduct from the earliest-expiring lots first (BR-INVD-10).
     * Called by the issue service after the primary stock movement.
     *
     * @param companyId  tenant
     * @param locationId issue location
     * @param productId  product
     * @param qty        positive quantity to consume
     * @param actorId    user performing the action
     */
    void consumeFefo(Long companyId, Long locationId, Long productId, BigDecimal qty, Long actorId);

    /**
     * Look up a batch by uid.
     */
    StockBatchDto getByUid(String uid);

    /**
     * Paged list of batches at a location for a product.
     */
    Page<StockBatchDto> listAtLocation(Long companyId, Long locationId, Long productId,
                                        Pageable pageable);

    /**
     * Expiry report: batches expiring on or before the horizon date with qty > 0.
     *
     * @param companyId tenant
     * @param horizon   cut-off date (inclusive)
     */
    Page<StockBatchDto> listExpiring(Long companyId, LocalDate horizon, Pageable pageable);
}
