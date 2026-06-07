package com.erp.modules.stock.repository;

import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.enums.MovementType;
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
}
