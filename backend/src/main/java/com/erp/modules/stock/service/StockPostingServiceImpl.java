package com.erp.modules.stock.service;

import com.erp.modules.stock.domain.entity.StockMovement;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.domain.enums.MovementType;
import com.erp.modules.stock.repository.StockMovementRepository;
import com.erp.modules.stock.repository.StockOnHandRepository;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * The single posting primitive (ADR-0010 D-4, extended ADR-0028 D-3).
 *
 * <p>Uses {@code MANDATORY} propagation — callers (handlers, StockService) must already have an
 * active transaction. This ensures the movement + on-hand delta commit atomically with whatever the
 * caller is doing (the idempotency marker for handlers, the audit write for manual ops, the outbox
 * event mark for the dispatcher).
 *
 * <p>On-hand is upserted: if no row exists for (company, branch, location, product) it is created
 * at qty 0 and the delta applied, making the first movement idempotent on the upsert path too.
 *
 * <p>Optimistic-lock retry (NFR-STOCK-04): if a concurrent update races the on-hand row version,
 * the operation retries once.
 *
 * <p>ADR-0028 D-3: {@code locationId} is now mandatory. Legacy callers that pass no locationId
 * use the deprecated overloads which leave locationId as null (the V38 migration backfills
 * existing rows; new rows should always supply a locationId via LocationResolver).
 */
@Service
public class StockPostingServiceImpl implements StockPostingService {

    private final StockMovementRepository movements;
    private final StockOnHandRepository   onHands;
    private final LocationResolver        locationResolver;

    public StockPostingServiceImpl(StockMovementRepository movements,
                                   StockOnHandRepository onHands,
                                   LocationResolver locationResolver) {
        this.movements        = movements;
        this.onHands          = onHands;
        this.locationResolver = locationResolver;
    }

    // -------------------------------------------------------------------------
    // Primary location-aware overloads (ADR-0028 D-3)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String post(Long companyId, Long branchId, Long locationId, Long productId,
                       BigDecimal quantity, MovementType movementType,
                       String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                       String reasonCode, String note, Instant occurredAt, Long actorId,
                       BigDecimal unitCostAmount, BigDecimal valueAmount) {
        return post(companyId, branchId, locationId, productId, quantity, movementType,
                sourceEventUid, sourceDocumentType, sourceDocumentUid,
                reasonCode, note, occurredAt, actorId, unitCostAmount, valueAmount,
                null, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String post(Long companyId, Long branchId, Long locationId, Long productId,
                       BigDecimal quantity, MovementType movementType,
                       String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                       String reasonCode, String note, Instant occurredAt, Long actorId,
                       BigDecimal unitCostAmount, BigDecimal valueAmount,
                       Long costCentreValueId, Long departmentValueId) {
        return post(companyId, branchId, locationId, productId, quantity, movementType,
                sourceEventUid, sourceDocumentType, sourceDocumentUid,
                reasonCode, note, occurredAt, actorId, unitCostAmount, valueAmount,
                costCentreValueId, departmentValueId, null, null);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String post(Long companyId, Long branchId, Long locationId, Long productId,
                       BigDecimal quantity, MovementType movementType,
                       String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                       String reasonCode, String note, Instant occurredAt, Long actorId,
                       BigDecimal unitCostAmount, BigDecimal valueAmount,
                       Long costCentreValueId, Long departmentValueId,
                       Long projectId, Long projectTaskId) {

        // FOLLOW-003: idempotency backstop — if a movement already exists for this
        // (source_event_uid, product_id) pair the outbox event was already applied.
        // Skip the insert AND the on-hand delta so the redelivered event is a no-op.
        // This prevents hitting the unique constraint uq_stock_movement_source_event on redelivery.
        // Only guard when sourceEventUid is non-null (manual ops have no event uid).
        //
        // The key MUST be unique per posting, not per event. While handlers passed the raw event uid
        // for every line, this check turned "second line of the same product" into "redelivery of
        // the first line" and silently dropped it — the document charged for both lines, COGS and
        // on_hand_value moved for both, and only one movement row (and one on-hand delta) existed.
        // Handlers now allocate a per-posting key via MovementSourceKeys; see its javadoc.
        if (sourceEventUid != null
                && movements.existsBySourceEventUidAndProductId(sourceEventUid, productId)) {
            // Return the uid of the existing movement (caller may log it but doesn't need to re-process).
            return movements.findBySourceEventUid(sourceEventUid).stream()
                    .filter(m -> productId.equals(m.getProductId()))
                    .map(StockMovement::getUid)
                    .findFirst()
                    .orElse(null);
        }

        // ADR-0028 D-3: legacy callers pass null; resolve the branch default transparently.
        if (locationId == null) {
            locationId = locationResolver.defaultLocationId(companyId, branchId);
        }

        // (1) Append the movement row — the immutable ledger entry.
        StockMovement movement = new StockMovement(
                companyId, branchId, locationId, productId,
                movementType, quantity,
                sourceEventUid, sourceDocumentType, sourceDocumentUid,
                reasonCode, note, occurredAt, actorId,
                unitCostAmount, valueAmount,
                costCentreValueId, departmentValueId,
                projectId, projectTaskId);
        movements.save(movement);

        // (2) Upsert the on-hand row — first touch creates it at qty 0, then delta is applied.
        try {
            applyOnHandDelta(companyId, branchId, locationId, productId, quantity, actorId);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // Retry once on a version clash (concurrent receipts racing an issue, NFR-STOCK-04).
            applyOnHandDelta(companyId, branchId, locationId, productId, quantity, actorId);
        }

        return movement.getUid();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean alreadyPosted(String sourceEventUid, Long productId) {
        return sourceEventUid != null
                && movements.existsBySourceEventUidAndProductId(sourceEventUid, productId);
    }

    // -------------------------------------------------------------------------
    // Legacy location-unaware overloads (backward compat — deprecated)
    // -------------------------------------------------------------------------

    @SuppressWarnings("deprecation")
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String post(Long companyId, Long branchId, Long productId,
                       BigDecimal quantity, MovementType movementType,
                       String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                       String reasonCode, String note, Instant occurredAt, Long actorId,
                       BigDecimal unitCostAmount, BigDecimal valueAmount) {
        return post(companyId, branchId, null, productId, quantity, movementType,
                sourceEventUid, sourceDocumentType, sourceDocumentUid,
                reasonCode, note, occurredAt, actorId, unitCostAmount, valueAmount,
                null, null);
    }

    @SuppressWarnings("deprecation")
    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String post(Long companyId, Long branchId, Long productId,
                       BigDecimal quantity, MovementType movementType,
                       String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                       String reasonCode, String note, Instant occurredAt, Long actorId,
                       BigDecimal unitCostAmount, BigDecimal valueAmount,
                       Long costCentreValueId, Long departmentValueId) {
        return post(companyId, branchId, null, productId, quantity, movementType,
                sourceEventUid, sourceDocumentType, sourceDocumentUid,
                reasonCode, note, occurredAt, actorId, unitCostAmount, valueAmount,
                costCentreValueId, departmentValueId);
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private void applyOnHandDelta(Long companyId, Long branchId, Long locationId,
                                   Long productId, BigDecimal delta, Long actorId) {
        StockOnHand soh;
        if (locationId != null) {
            soh = onHands
                    .findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                            companyId, branchId, locationId, productId)
                    .orElseGet(() -> {
                        @SuppressWarnings("deprecation")
                        StockOnHand fresh = new StockOnHand(companyId, branchId, locationId, productId);
                        return onHands.save(fresh);
                    });
        } else {
            // Legacy path — no location (pre-ADR-0028 callers; will be migrated over time)
            @SuppressWarnings("deprecation")
            StockOnHand found = onHands
                    .findByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId)
                    .orElseGet(() -> {
                        @SuppressWarnings("deprecation")
                        StockOnHand fresh = new StockOnHand(companyId, branchId, productId);
                        return onHands.save(fresh);
                    });
            soh = found;
        }
        soh.applyDelta(delta, actorId);
        onHands.save(soh);
    }
}
