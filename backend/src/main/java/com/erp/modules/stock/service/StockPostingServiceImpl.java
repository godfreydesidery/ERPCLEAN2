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
 * The single posting primitive (ADR-0010 D-4).
 *
 * <p>Uses {@code MANDATORY} propagation — callers (handlers, StockService) must already have an
 * active transaction. This ensures the movement + on-hand delta commit atomically with whatever the
 * caller is doing (the idempotency marker for handlers, the audit write for manual ops, the outbox
 * event mark for the dispatcher).
 *
 * <p>On-hand is upserted: if no row exists for (company, branch, product) it is created at qty 0
 * and the delta applied, making the first movement idempotent on the upsert path too.
 *
 * <p>Optimistic-lock retry (NFR-STOCK-04): if a concurrent update races the on-hand row version,
 * the operation retries once. Under the single-instance QA setup this is a belt-and-braces guard;
 * it becomes the liveness mechanism under multi-instance (before SKIP LOCKED lands).
 */
@Service
public class StockPostingServiceImpl implements StockPostingService {

    private final StockMovementRepository movements;
    private final StockOnHandRepository onHands;

    public StockPostingServiceImpl(StockMovementRepository movements,
                                   StockOnHandRepository onHands) {
        this.movements = movements;
        this.onHands   = onHands;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String post(Long companyId, Long branchId, Long productId,
                       BigDecimal quantity, MovementType movementType,
                       String sourceEventUid, String sourceDocumentType, String sourceDocumentUid,
                       String reasonCode, String note, Instant occurredAt, Long actorId) {

        // (1) Append the movement row — the immutable ledger entry.
        StockMovement movement = new StockMovement(
                companyId, branchId, productId,
                movementType, quantity,
                sourceEventUid, sourceDocumentType, sourceDocumentUid,
                reasonCode, note, occurredAt, actorId);
        movements.save(movement);

        // (2) Upsert the on-hand row — first touch creates it at qty 0, then delta is applied.
        try {
            applyOnHandDelta(companyId, branchId, productId, quantity, actorId);
        } catch (ObjectOptimisticLockingFailureException ex) {
            // Retry once on a version clash (concurrent receipts racing an issue, NFR-STOCK-04).
            applyOnHandDelta(companyId, branchId, productId, quantity, actorId);
        }

        return movement.getUid();
    }

    private void applyOnHandDelta(Long companyId, Long branchId, Long productId,
                                   BigDecimal delta, Long actorId) {
        StockOnHand soh = onHands
                .findByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId)
                .orElseGet(() -> {
                    StockOnHand fresh = new StockOnHand(companyId, branchId, productId);
                    return onHands.save(fresh);
                });
        soh.applyDelta(delta, actorId); // actorId null for system; entity handles it
        onHands.save(soh);
    }
}
