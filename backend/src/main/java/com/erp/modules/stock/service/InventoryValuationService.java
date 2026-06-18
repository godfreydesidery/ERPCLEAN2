package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.dto.OpeningValuationResultDto;
import com.erp.modules.stock.domain.dto.SetOpeningValuationRequest;
import com.erp.modules.stock.domain.entity.StockOnHand;
import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * Moving-average cost authority for the stock module (ADR-0020 D-2/D-5/D-5b/D-7).
 *
 * <p>Every path that changes the inventory value calls into this service:
 * <ul>
 *   <li>{@link #recomputeOnReceipt} — called by the goods-receipt handler after qty delta.</li>
 *   <li>{@link #costIssue} — called by sale-issue and adjustment-out handlers to debit inventory
 *       at the current avg; updates {@code on_hand_value} in place.</li>
 *   <li>{@link #reverseIssue} — called by sale-reversal handler to restore value at original cost.</li>
 *   <li>{@link #reverseReceipt} — called by receipt-reversal handler to back out value at original cost.</li>
 *   <li>{@link #setOpeningValue} — operator once-per-product opening valuation.</li>
 *   <li>{@link #revalueAdjustment} — called by {@code StockServiceImpl.adjust} for the GL leg.</li>
 * </ul>
 */
public interface InventoryValuationService {

    /**
     * Recompute the moving-average cost on a goods receipt (BR-INV-01, ADR-0020 D-2).
     *
     * <p>Runs inside the receipt handler's MANDATORY TX, under the {@code @Version} optimistic lock
     * on the on-hand row, with one retry on {@code ObjectOptimisticLockingFailureException}.
     *
     * <p>Returns the receipt value (qty × receipt cost, HALF_UP 4 dp) used for the GL leg.
     *
     * @param companyId       tenant
     * @param branchId        branch (cost is per company-product; branch scopes the on-hand row)
     * @param productId       the product
     * @param receiptQty      positive receipt quantity in base units
     * @param receiptCost     unit cost from the payload (goods_receipt_lines.unit_cost_amount)
     * @return receipt value amount for the GL leg (Σ qty × cost for the receipt line)
     */
    BigDecimal recomputeOnReceipt(Long companyId, Long branchId, Long productId,
                                   BigDecimal receiptQty, BigDecimal receiptCost);

    /**
     * Apply a cost-issue delta: debit on_hand_value by qty × current avg_cost (ADR-0020 D-4b).
     * Returns the issued value amount (positive absolute value) or {@code null} when avg_cost is
     * NULL (COGS leg skipped per D-2 edge — caller logs WARN + anomaly).
     *
     * @param companyId  tenant
     * @param branchId   branch
     * @param productId  product
     * @param issuedQty  positive magnitude of the issued quantity (caller negates for the movement)
     * @return issued value (&gt;=0) or null when avg_cost is not established
     */
    BigDecimal costIssue(Long companyId, Long branchId, Long productId, BigDecimal issuedQty);

    /**
     * Reverse a prior issue: restore on_hand_value by the original value amount (ADR-0020 D-5).
     * avg_cost is left unchanged (an issue never moved it).
     *
     * @param companyId       tenant
     * @param branchId        branch
     * @param productId       product
     * @param originalValue   absolute value of the original SALE_ISSUE movement's value_amount
     *                        (positive — the restore amount)
     */
    void reverseIssue(Long companyId, Long branchId, Long productId, BigDecimal originalValue);

    /**
     * Reverse a prior receipt: back out on_hand_value and recompute avg_cost (ADR-0020 D-5).
     * Symmetric inverse of recomputeOnReceipt.
     *
     * @param companyId      tenant
     * @param branchId       branch
     * @param productId      product
     * @param originalQty    positive magnitude of the original GOODS_RECEIPT quantity
     * @param originalValue  positive value of the original GOODS_RECEIPT movement's value_amount
     */
    void reverseReceipt(Long companyId, Long branchId, Long productId,
                         BigDecimal originalQty, BigDecimal originalValue);

    /**
     * One-time opening valuation (ADR-0020 D-5b, FR-INV-06): set avg_cost + on_hand_value for
     * an existing quantity-only on-hand row, post DR INVENTORY / CR OPENING_BALANCE_EQUITY.
     * Gated {@code INVENTORY.OPENING.SET}; idempotency guard = avg_cost IS NOT NULL or value != 0.
     */
    OpeningValuationResultDto setOpeningValue(SetOpeningValuationRequest request,
                                               LocalDate postingDate);

    /**
     * Revalue a stock adjustment: post DR STOCK_ADJUSTMENT / CR INVENTORY (decrease) or reverse
     * (increase) at the current avg_cost (ADR-0020 D-7, FR-INV-08).
     * Synchronous in the operator's TX — missing gl_config fails the command (BR-INV-12).
     *
     * <p>ADR-0025 D-6: {@code costCentreValueId} and {@code departmentValueId} are optional
     * dimension ids for the P&L expense leg; null = untagged (NFR-CC-01).
     *
     * <p>FOLLOW-001: {@code productCode} and {@code reasonCode} are used for human-readable GL
     * memo text; raw ULID is never embedded in a visible journal description.
     *
     * @param movementUid        the uid of the ADJUSTMENT movement (used as GL sourceRef)
     * @param soh                the on-hand row (already loaded by StockServiceImpl)
     * @param adjustQty          the signed adjustment quantity
     * @param postingDate        the posting date for the GL entry
     * @param costCentreValueId  nullable dimension id for cost-centre tagging
     * @param departmentValueId  nullable dimension id for department tagging
     * @param productCode        human-readable product code for GL memo (nullable)
     * @param reasonCode         adjustment reason code for GL memo (nullable)
     */
    void revalueAdjustment(String movementUid, StockOnHand soh, BigDecimal adjustQty,
                            LocalDate postingDate,
                            Long costCentreValueId, Long departmentValueId,
                            String productCode, String reasonCode);

    /**
     * Overload without productCode/reasonCode — dimension-tagged callers that predate FOLLOW-001.
     */
    default void revalueAdjustment(String movementUid, StockOnHand soh, BigDecimal adjustQty,
                                   LocalDate postingDate,
                                   Long costCentreValueId, Long departmentValueId) {
        revalueAdjustment(movementUid, soh, adjustQty, postingDate,
                costCentreValueId, departmentValueId, null, null);
    }

    /**
     * Backward-compatible overload — calls the full form with null dimension ids (NFR-CC-01).
     */
    default void revalueAdjustment(String movementUid, StockOnHand soh, BigDecimal adjustQty,
                                   LocalDate postingDate) {
        revalueAdjustment(movementUid, soh, adjustQty, postingDate, null, null, null, null);
    }

    /**
     * Move cost value from a source stock-on-hand row to a destination row (ADR-0028 D-5).
     *
     * <p>Used by the stock-transfer completion and dispatch/receive handlers to keep
     * {@code on_hand_value} consistent with quantity movements. The transfer quantity is costed at
     * the source location's current {@code avg_cost}; the same value is removed from the source row
     * and added to the destination row. The destination's {@code avg_cost} is recomputed as a
     * weighted average of its pre-transfer value plus the incoming value.
     *
     * <p>If the source location has no {@code avg_cost} established (null), the cost move is a
     * no-op (no value to transfer — consistent with D-2 zero-cost-receipt edge). The destination
     * retains its existing avg_cost (or remains null).
     *
     * <p>REQUIRES MANDATORY propagation — callers must hold an active transaction.
     *
     * @param companyId       tenant company
     * @param srcBranchId     source branch
     * @param srcLocationId   source physical location
     * @param destBranchId    destination branch
     * @param destLocationId  destination physical location
     * @param productId       the product being transferred
     * @param qty             transfer quantity (positive)
     */
    void transferCost(Long companyId,
                      Long srcBranchId, Long srcLocationId,
                      Long destBranchId, Long destLocationId,
                      Long productId, BigDecimal qty);

    /**
     * Apply a landed-cost allocation to a single stock-on-hand row (ADR-0027 D-5 / BR-PROC-11).
     *
     * <p>Adds {@code allocatedAmount} to {@code on_hand_value} and recomputes {@code avg_cost}.
     * Called from {@link com.erp.modules.stock.events.LandedCostStockHandler} per landed-cost
     * allocation line (one call per GR line).
     *
     * @param companyId       tenant
     * @param branchId        branch (scopes the on-hand row)
     * @param productId       product whose avg_cost is adjusted
     * @param allocatedAmount the landed-cost share to add to on_hand_value (positive)
     */
    void applyLandedCost(Long companyId, Long branchId, Long productId, BigDecimal allocatedAmount);
}
