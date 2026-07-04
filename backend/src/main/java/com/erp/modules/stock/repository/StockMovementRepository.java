package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.dto.VanMovementSumRowDto;
import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.enums.MovementType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Spring Data repository for {@link StockMovement} (append-only ledger).
 *
 * <p>No update or delete methods are exposed here (BR-STOCK-06: movements are immutable;
 * compensating movements correct, never in-place edits). The interface intentionally inherits only
 * the read + save surface from {@link JpaRepository}.
 */
public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {

    /** uid-lookup — the ledger drill-down endpoint (D-11). */
    Optional<StockMovement> findByUid(String uid);

    /**
     * Find the SALE_ISSUE movements posted for a specific source document (invoice uid).
     * Used by {@link com.erp.modules.stock.events.SaleReversalStockHandler} to reverse exactly
     * what was issued (OQ-STOCK-10 — reverse only what was applied).
     */
    List<StockMovement> findBySourceDocumentUidAndMovementType(
            String sourceDocumentUid, MovementType movementType);

    /**
     * Find movements by source document uid (used for reversal lookups across movement types).
     * The reversal handlers filter further by movement type.
     */
    @Query("SELECT m FROM StockMovement m WHERE m.sourceDocumentUid = :sourceDocumentUid AND m.movementType = :movementType ORDER BY m.occurredAt ASC")
    List<StockMovement> findIssuedMovements(
            @Param("sourceDocumentUid") String sourceDocumentUid,
            @Param("movementType") MovementType movementType);

    /**
     * Paged movement ledger for a product at the active branch (FR-STOCK-11, chronological).
     * Tenant-scoped: company + branch + product.
     */
    Page<StockMovement> findByCompanyIdAndBranchIdAndProductIdOrderByOccurredAtAsc(
            Long companyId, Long branchId, Long productId, Pageable pageable);

    /**
     * Single-column projection: the company owning a stock movement row identified by uid.
     * Used by {@link com.erp.platform.security.ScopeGuard#companyIdOf} (D-10).
     */
    @Query("SELECT m.companyId FROM StockMovement m WHERE m.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /**
     * Find all movements for a given source event uid. Used for diagnostics and the
     * (source_event_uid, product_id) idempotency backstop query.
     */
    List<StockMovement> findBySourceEventUid(String sourceEventUid);

    /**
     * Idempotency backstop check (FOLLOW-003): returns true when a movement already exists for
     * the given (source_event_uid, product_id) pair. Used by StockPostingServiceImpl before
     * inserting a new movement, so that redelivered events skip double-application instead of
     * hitting the unique constraint {@code uq_stock_movement_source_event}.
     *
     * <p>The source_event_uid may be null for manual adjustments; null never matches here
     * (Spring Data does not generate an {@code IS NULL} predicate with a non-null parameter).
     */
    boolean existsBySourceEventUidAndProductId(String sourceEventUid, Long productId);

    /**
     * Per-product sum of a single movement type at a location within a business-time window
     * (ADR-0051 D-8.6) — the van-reconciliation derivation query. {@code loaded_qty} =
     * {@code sumByLocationTypeAndWindow(..., TRANSFER_IN, ...)}; {@code returned_qty} =
     * {@code -sumByLocationTypeAndWindow(..., TRANSFER_OUT, ...)} (TRANSFER_OUT rows are already
     * signed negative). The {@code [from, to)} window is the business date at UTC day boundaries.
     */
    @Query("""
            SELECT new com.erp.modules.stock.domain.dto.VanMovementSumRowDto(
                m.productId, COALESCE(SUM(m.quantity), 0))
            FROM StockMovement m
            WHERE m.companyId = :companyId AND m.branchId = :branchId
              AND m.locationId = :locationId
              AND m.movementType = :type
              AND m.occurredAt >= :from AND m.occurredAt < :to
            GROUP BY m.productId
            """)
    List<VanMovementSumRowDto> sumByLocationTypeAndWindow(
            @Param("companyId") Long companyId,
            @Param("branchId") Long branchId,
            @Param("locationId") Long locationId,
            @Param("type") MovementType type,
            @Param("from") Instant from,
            @Param("to") Instant to);
}
