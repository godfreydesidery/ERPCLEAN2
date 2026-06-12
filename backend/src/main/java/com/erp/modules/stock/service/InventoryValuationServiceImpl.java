package com.erp.modules.stock.service;

import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.stock.domain.dto.OpeningValuationResultDto;
import com.erp.modules.stock.domain.dto.SetOpeningValuationRequest;
import com.erp.modules.stock.domain.entity.StockOnHand;
import com.erp.modules.stock.repository.StockOnHandRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moving-average cost engine (ADR-0020 D-2/D-5/D-5b/D-7).
 *
 * <p>Concurrency: the recompute is a read-modify-write on the {@code stock_on_hand} row that
 * already carries {@code @Version}. A clash raises {@link ObjectOptimisticLockingFailureException};
 * a single retry re-reads fresh state and recomputes against it. No SELECT FOR UPDATE (ADR-0020
 * D-2 / NFR-INV-05 decision).
 *
 * <p>Rounding: 4 dp HALF_UP throughout (OQ-INV-06). The stored {@code on_hand_value} is the
 * authoritative figure; {@code avg_cost} is derived to 4 dp and stored.
 *
 * <p>FIX A (adversarial review): {@link #reverseReceipt} computes postReversalQty as
 * {@code soh.getQuantity().subtract(originalQty.abs())} — the posting service applies the negative
 * delta AFTER this method returns, so soh holds the PRE-reversal qty at call time.
 *
 * <p>FIX D (adversarial review): {@link #costIssue}, {@link #reverseIssue},
 * {@link #reverseReceipt}, and {@link #revalueAdjustment} each have a public retry wrapper and a
 * private {@code doXxx()} implementation, mirroring {@link #recomputeOnReceipt}.
 *
 * <p>FIX G (adversarial review): {@link #costIssue} guards against zero issued qty to avoid
 * division by zero in the unit-cost re-derivation in {@link com.erp.modules.stock.events.SaleIssueStockHandler}.
 */
@Service
@Transactional(propagation = Propagation.MANDATORY)
public class InventoryValuationServiceImpl implements InventoryValuationService {

    private static final Logger log = LoggerFactory.getLogger(InventoryValuationServiceImpl.class);
    private static final int SCALE = 4;
    private static final RoundingMode RM = RoundingMode.HALF_UP;
    /** Base currency code used in GL line memos — sourced from context in operator paths. */
    private static final String BASE_CURRENCY = "TZS";
    /** Retry-log template shared by all cost-mutation retry wrappers (FIX D). */
    private static final String RETRY_MSG =
            "company={} product={} — retrying once (FIX D / NFR-INV-05)";

    private final StockOnHandRepository onHands;
    private final InventoryGlPoster     glPoster;
    private final LocationResolver      locationResolver;
    private final ScopeGuard            scopeGuard;
    private final AuditService          audit;

    public InventoryValuationServiceImpl(StockOnHandRepository onHands,
                                          InventoryGlPoster glPoster,
                                          LocationResolver locationResolver,
                                          ScopeGuard scopeGuard,
                                          AuditService audit) {
        this.onHands          = onHands;
        this.glPoster         = glPoster;
        this.locationResolver = locationResolver;
        this.scopeGuard       = scopeGuard;
        this.audit            = audit;
    }

    // -------------------------------------------------------------------------
    // (a) Recompute on receipt (ADR-0020 D-2 pseudocode)
    // -------------------------------------------------------------------------

    @Override
    public BigDecimal recomputeOnReceipt(Long companyId, Long branchId, Long productId,
                                          BigDecimal receiptQty, BigDecimal receiptCost) {
        try {
            return doRecomputeOnReceipt(companyId, branchId, productId, receiptQty, receiptCost);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.debug("InventoryValuation: optimistic lock clash on receipt recompute " +
                              "company={} product={} — retrying once (NFR-INV-05)", companyId, productId);
            return doRecomputeOnReceipt(companyId, branchId, productId, receiptQty, receiptCost);
        }
    }

    private BigDecimal doRecomputeOnReceipt(Long companyId, Long branchId, Long productId,
                                              BigDecimal receiptQty, BigDecimal receiptCost) {
        // Defensive: null cost treated as zero-cost receipt (D-3 backward note)
        BigDecimal cost = receiptCost != null ? receiptCost : BigDecimal.ZERO;
        if (cost.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("InventoryValuation: zero-cost receipt for company={} product={} — " +
                             "avg will drift toward zero (BR-INV-01 edge (b))", companyId, productId);
        }

        StockOnHand soh = onHands.findByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId)
                .orElseGet(() -> {
                    Long locId = locationResolver.defaultLocationId(companyId, branchId);
                    return onHands.save(new StockOnHand(companyId, branchId, locId, productId));
                });

        BigDecimal qty        = soh.getQuantity();           // current on-hand qty (before this receipt)
        BigDecimal avgCost    = soh.getAvgCost();            // may be null
        BigDecimal onHandVal  = soh.getOnHandValue() != null ? soh.getOnHandValue() : BigDecimal.ZERO;

        BigDecimal receiptValue = round4(receiptQty.multiply(cost));
        BigDecimal newQty = qty.add(receiptQty);

        BigDecimal newAvg;
        BigDecimal newValue;

        if (newQty.compareTo(BigDecimal.ZERO) <= 0) {
            // Pathological: receipt into deep-negative — guard, do not divide by <= 0
            newAvg   = (avgCost != null) ? avgCost : cost;
            newValue = onHandVal.add(receiptValue);
        } else if (avgCost == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            // First receipt, or receipt onto zero/negative on-hand — reset to receipt cost
            newAvg   = cost;
            newValue = round4(newQty.multiply(cost));
        } else {
            // Normal weighted-average recompute: new_avg = (on_hand_value + recv_value) / new_qty
            newValue = onHandVal.add(receiptValue);
            newAvg   = round4(newValue.divide(newQty, SCALE, RM));
        }

        soh.applyCostRecompute(newAvg, newValue, null);
        onHands.save(soh);

        return receiptValue;
    }

    // -------------------------------------------------------------------------
    // (b) Cost-issue: debit on_hand_value at current avg (ADR-0020 D-4b)
    //     FIX D: public wrapper retries once on optimistic lock clash.
    //     FIX G: guard against zero issued qty (div-by-zero in unit-cost re-derivation).
    // -------------------------------------------------------------------------

    @Override
    public BigDecimal costIssue(Long companyId, Long branchId, Long productId,
                                 BigDecimal issuedQty) {
        try {
            return doCostIssue(companyId, branchId, productId, issuedQty);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.debug("InventoryValuation: optimistic lock clash on costIssue " + RETRY_MSG,
                    companyId, productId);
            return doCostIssue(companyId, branchId, productId, issuedQty);
        }
    }

    private BigDecimal doCostIssue(Long companyId, Long branchId, Long productId,
                                    BigDecimal issuedQty) {
        // FIX G: zero issued qty — skip COGS leg, no division
        if (issuedQty == null || issuedQty.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("InventoryValuation: costIssue — issuedQty is zero for company={} product={} " +
                             "— COGS leg skipped (FIX G div-guard)", companyId, productId);
            return null;
        }

        StockOnHand soh = onHands.findByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId)
                .orElse(null);

        if (soh == null || soh.getAvgCost() == null) {
            log.warn("InventoryValuation: costIssue — avg_cost IS NULL for company={} product={} " +
                             "— COGS leg skipped (D-2 edge)", companyId, productId);
            return null;  // caller skips GL leg + logs anomaly
        }

        BigDecimal issuedValue = round4(issuedQty.multiply(soh.getAvgCost()));
        BigDecimal newValue    = soh.getOnHandValue().subtract(issuedValue);
        // avg_cost is unchanged on issue (BR-INV-01)
        soh.applyCostRecompute(soh.getAvgCost(), newValue, null);
        onHands.save(soh);

        return issuedValue;
    }

    // -------------------------------------------------------------------------
    // (c) Reverse issue: restore on_hand_value at original cost (ADR-0020 D-5)
    //     FIX D: public wrapper retries once on optimistic lock clash.
    // -------------------------------------------------------------------------

    @Override
    public void reverseIssue(Long companyId, Long branchId, Long productId,
                              BigDecimal originalValue) {
        try {
            doReverseIssue(companyId, branchId, productId, originalValue);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.debug("InventoryValuation: optimistic lock clash on reverseIssue " + RETRY_MSG,
                    companyId, productId);
            doReverseIssue(companyId, branchId, productId, originalValue);
        }
    }

    private void doReverseIssue(Long companyId, Long branchId, Long productId,
                                 BigDecimal originalValue) {
        StockOnHand soh = onHands.findByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId)
                .orElse(null);
        if (soh == null) return; // no on-hand row — cannot restore (shouldn't happen)

        BigDecimal newValue = soh.getOnHandValue().add(originalValue);
        // avg_cost unchanged — an issue never moved it; reversal also does not (D-5)
        soh.applyCostRecompute(soh.getAvgCost(), newValue, null);
        onHands.save(soh);
    }

    // -------------------------------------------------------------------------
    // (d) Reverse receipt: back out on_hand_value + recompute avg (ADR-0020 D-5)
    //     FIX A: postReversalQty = soh.getQuantity().subtract(originalQty.abs())
    //            because StockPostingService applies the negative qty delta AFTER this
    //            method returns — soh holds the PRE-reversal qty at call time.
    //     FIX D: public wrapper retries once on optimistic lock clash.
    // -------------------------------------------------------------------------

    @Override
    public void reverseReceipt(Long companyId, Long branchId, Long productId,
                                BigDecimal originalQty, BigDecimal originalValue) {
        try {
            doReverseReceipt(companyId, branchId, productId, originalQty, originalValue);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.debug("InventoryValuation: optimistic lock clash on reverseReceipt " + RETRY_MSG,
                    companyId, productId);
            doReverseReceipt(companyId, branchId, productId, originalQty, originalValue);
        }
    }

    private void doReverseReceipt(Long companyId, Long branchId, Long productId,
                                   BigDecimal originalQty, BigDecimal originalValue) {
        StockOnHand soh = onHands.findByCompanyIdAndBranchIdAndProductId(companyId, branchId, productId)
                .orElse(null);
        if (soh == null) return;

        BigDecimal onHandVal = soh.getOnHandValue() != null ? soh.getOnHandValue() : BigDecimal.ZERO;

        BigDecimal newValue = onHandVal.subtract(originalValue);

        // FIX A: StockPostingService applies the negative qty delta AFTER this method is called
        // (in GoodsReceiptReversalStockHandler, valuation.reverseReceipt runs before posting.post).
        // soh.getQuantity() is still the PRE-reversal qty here — subtract the original receipt qty
        // to derive the POST-reversal qty (ADR-0020 D-5: newQty = qty − original.qty).
        BigDecimal postReversalQty = soh.getQuantity().subtract(originalQty.abs());

        BigDecimal newAvg;
        if (postReversalQty.compareTo(BigDecimal.ZERO) > 0) {
            newAvg = round4(newValue.divide(postReversalQty, SCALE, RM));
        } else {
            newAvg = soh.getAvgCost(); // keep last-known if empties to <= 0 (D-5)
        }

        soh.applyCostRecompute(newAvg, newValue, null);
        onHands.save(soh);
    }

    // -------------------------------------------------------------------------
    // (e) Opening valuation (ADR-0020 D-5b)
    // -------------------------------------------------------------------------

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public OpeningValuationResultDto setOpeningValue(SetOpeningValuationRequest request,
                                                      LocalDate postingDate) {
        RequestContext.Principal principal = RequestContext.get();

        StockOnHand soh = onHands.findByUid(request.stockOnHandUid())
                .orElseThrow(() -> NotFoundException.of("StockOnHand", request.stockOnHandUid()));

        scopeGuard.assertCanActIn(principal, soh.getCompanyId());

        // Idempotency guard: reject if already valued (BR-INV-07 — once per product)
        boolean alreadyValued = (soh.getAvgCost() != null)
                || soh.getOnHandValue().compareTo(BigDecimal.ZERO) != 0;
        if (alreadyValued) {
            throw new IllegalStateException(
                    "Stock on-hand uid=" + request.stockOnHandUid()
                            + " already has a valuation (avg_cost=" + soh.getAvgCost()
                            + " on_hand_value=" + soh.getOnHandValue()
                            + "). Opening valuation is once-per-product (BR-INV-07).");
        }

        BigDecimal openingCost  = request.openingCost();
        BigDecimal qty          = soh.getQuantity();
        BigDecimal openingValue = qty.compareTo(BigDecimal.ZERO) == 0
                ? BigDecimal.ZERO
                : round4(qty.multiply(openingCost));

        soh.applyCostRecompute(openingCost, openingValue, actorId(principal));
        onHands.save(soh);

        // Post GL only if value > 0; if qty is 0, set cost only and skip GL (D-5b)
        String glEntryUid = null;
        if (openingValue.compareTo(BigDecimal.ZERO) > 0) {
            JournalEntryDto glResult = glPoster.postOpeningValuationDirect(
                    soh.getCompanyId(), soh.getBranchId(), postingDate,
                    soh.getUid(), BASE_CURRENCY, openingValue, actorId(principal));
            glEntryUid = glResult != null ? glResult.uid() : null;
        }

        audit.record(AuditEvent.of(AuditActions.STOCK_ADJUST, "stock_on_hand",
                        soh.getId(), soh.getUid())
                .detail(Map.of(
                        "action",        "openingValuation",
                        "openingCost",   openingCost.toPlainString(),
                        "openingValue",  openingValue.toPlainString(),
                        "qty",           qty.toPlainString(),
                        "glEntryUid",    glEntryUid != null ? glEntryUid : "none"
                )));

        return new OpeningValuationResultDto(
                null,   // productUid — caller can enrich from product service if needed
                qty, openingCost, openingValue, glEntryUid, BASE_CURRENCY);
    }

    // -------------------------------------------------------------------------
    // (f) Adjustment revaluation (ADR-0020 D-7)
    //     FIX D: public wrapper retries once on optimistic lock clash.
    // -------------------------------------------------------------------------

    @Override
    public void revalueAdjustment(String movementUid, StockOnHand soh, BigDecimal adjustQty,
                                   LocalDate postingDate,
                                   Long costCentreValueId, Long departmentValueId) {
        try {
            doRevalueAdjustment(movementUid, soh, adjustQty, postingDate,
                    costCentreValueId, departmentValueId);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.debug("InventoryValuation: optimistic lock clash on revalueAdjustment " +
                              "company={} movement={} — retrying once (FIX D / NFR-INV-05)",
                    soh.getCompanyId(), movementUid);
            // Re-read fresh soh state before retry
            StockOnHand freshSoh = onHands.findByCompanyIdAndBranchIdAndProductId(
                    soh.getCompanyId(), soh.getBranchId(), soh.getProductId()).orElse(soh);
            doRevalueAdjustment(movementUid, freshSoh, adjustQty, postingDate,
                    costCentreValueId, departmentValueId);
        }
    }

    private void doRevalueAdjustment(String movementUid, StockOnHand soh, BigDecimal adjustQty,
                                      LocalDate postingDate,
                                      Long costCentreValueId, Long departmentValueId) {
        if (soh.getAvgCost() == null) {
            log.warn("InventoryValuation: revalueAdjustment — avg_cost IS NULL for company={} product={} " +
                             "movement={} — GL leg skipped (D-2 edge)", soh.getCompanyId(), soh.getProductId(), movementUid);
            return;
        }

        BigDecimal avgCost = soh.getAvgCost();
        BigDecimal absQty  = adjustQty.abs();
        BigDecimal value   = round4(absQty.multiply(avgCost));
        boolean decrease   = adjustQty.compareTo(BigDecimal.ZERO) < 0;

        // Update on_hand_value: decrease subtracts, increase adds (avg unchanged — BR-INV-09)
        BigDecimal newValue = decrease
                ? soh.getOnHandValue().subtract(value)
                : soh.getOnHandValue().add(value);
        soh.applyCostRecompute(soh.getAvgCost(), newValue, actorId(RequestContext.get()));
        onHands.save(soh);

        // Post GL directly — a missing config MUST fail the operator's command (BR-INV-12)
        // ADR-0025 D-6: pass dimension ids to tag the expense leg (null = untagged)
        glPoster.postAdjustmentDirect(
                soh.getCompanyId(), soh.getBranchId(), postingDate,
                new InventoryGlPoster.AdjustmentPostCmd(
                        movementUid, BASE_CURRENCY, value, decrease,
                        actorId(RequestContext.get()),
                        costCentreValueId, departmentValueId));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private static BigDecimal round4(BigDecimal v) {
        return v.setScale(SCALE, RM);
    }

    private static Long actorId(RequestContext.Principal p) {
        return p != null ? p.userId() : null;
    }
}
