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
import java.util.List;
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
 * <p>Concurrency: the recompute is a read-modify-write on the {@code stock_on_hand} rows that
 * carry {@code @Version}. A clash raises {@link ObjectOptimisticLockingFailureException};
 * a single retry re-reads fresh state and recomputes against it. No SELECT FOR UPDATE (ADR-0020
 * D-2 / NFR-INV-05 decision).
 *
 * <p>Rounding: 4 dp HALF_UP throughout (OQ-INV-06). The stored {@code on_hand_value} is the
 * authoritative figure; {@code avg_cost} is derived to 4 dp and stored.
 *
 * <p><b>ADR-0028 D-2 FIX — Findings 1 &amp; 2 (adversarial review)</b>: avg_cost is
 * per-company-product, not per-location. On every receipt (or receipt reversal), ALL location rows
 * for the (company, product) are loaded via {@link StockOnHandRepository#findByCompanyIdAndProductId},
 * the aggregate Σ qty and Σ on_hand_value are computed, the new avg is derived, and then EVERY
 * location row is updated with the same avg_cost and its re-attributed on_hand_value
 * ({@code qty × new_avg}). A receipt at LOC1 when stock also sits at LOC2 must propagate the same
 * avg_cost to LOC2 — failing to do so lets the avg diverge per-location, breaking the
 * {@code Σ on_hand_value == accountBalance(1300)} recon invariant (ADR-0020).
 *
 * <p>FIX A (adversarial review): {@link #reverseReceipt} computes postReversalQty as the
 * aggregate total minus the original qty — the posting service applies the negative delta AFTER
 * this method returns, so rows hold PRE-reversal quantities at call time.
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
    // (a) Recompute on receipt (ADR-0020 D-2 pseudocode + ADR-0028 D-2 fix)
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

    /**
     * ADR-0028 D-2 FIX (Finding 1): loads ALL location rows for (company, product), aggregates
     * Σ qty and Σ on_hand_value across the whole company, computes one new avg, then syncs every
     * location row. The receiving location row is upserted first so it participates in the
     * aggregate.
     */
    private BigDecimal doRecomputeOnReceipt(Long companyId, Long branchId, Long productId,
                                              BigDecimal receiptQty, BigDecimal receiptCost) {
        // Defensive: null cost treated as zero-cost receipt (D-3 backward note)
        BigDecimal cost = receiptCost != null ? receiptCost : BigDecimal.ZERO;
        if (cost.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("InventoryValuation: zero-cost receipt for company={} product={} — " +
                             "avg will drift toward zero (BR-INV-01 edge (b))", companyId, productId);
        }

        BigDecimal receiptValue = round4(receiptQty.multiply(cost));

        // Ensure the receiving location row exists before we aggregate (upsert if absent).
        // The posting service will add the qty delta AFTER we return, but avg_cost must be
        // computed INCLUDING this receipt's contribution now.
        Long receivingLocId = locationResolver.defaultLocationId(companyId, branchId);
        StockOnHand receivingRow = onHands
                .findByCompanyIdAndBranchIdAndLocationIdAndProductId(companyId, branchId, receivingLocId, productId)
                .orElseGet(() -> {
                    StockOnHand fresh = new StockOnHand(companyId, branchId, receivingLocId, productId);
                    return onHands.save(fresh);
                });

        // Load ALL location rows for this company-product (ADR-0028 D-2).
        List<StockOnHand> allRows = onHands.findByCompanyIdAndProductId(companyId, productId);

        // Aggregate pre-receipt totals (Σ qty, Σ on_hand_value across all locations).
        BigDecimal totalQty   = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        boolean hasExistingAvg = false;
        for (StockOnHand row : allRows) {
            totalQty   = totalQty.add(row.getQuantity());
            totalValue = totalValue.add(row.getOnHandValue() != null ? row.getOnHandValue() : BigDecimal.ZERO);
            if (row.getAvgCost() != null) hasExistingAvg = true;
        }

        // Compute new company-product avg (same formula as single-row, but on aggregated totals).
        BigDecimal newQty = totalQty.add(receiptQty);
        BigDecimal newAvg;
        BigDecimal newTotalValue;

        if (newQty.compareTo(BigDecimal.ZERO) <= 0) {
            // Pathological: receipt into deep-negative aggregate — keep prior avg, add value
            newAvg        = hasExistingAvg
                    ? allRows.stream().filter(r -> r.getAvgCost() != null)
                              .findFirst().map(StockOnHand::getAvgCost).orElse(cost)
                    : cost;
            newTotalValue = totalValue.add(receiptValue);
        } else if (!hasExistingAvg || totalQty.compareTo(BigDecimal.ZERO) <= 0) {
            // First receipt, or receipt onto zero/negative aggregate — reset to receipt cost
            newAvg        = cost;
            newTotalValue = round4(newQty.multiply(cost));
        } else {
            // Normal weighted-average recompute: new_avg = (total_value + recv_value) / new_total_qty
            newTotalValue = totalValue.add(receiptValue);
            newAvg        = round4(newTotalValue.divide(newQty, SCALE, RM));
        }

        // Sync new avg_cost and re-attributed on_hand_value to EVERY location row.
        // For non-receiving rows: on_hand_value = row.qty × new_avg (their qty is stable).
        // For the receiving row: the posting service will add receiptQty AFTER this method
        // returns, so the row still holds its PRE-receipt qty here. We must pre-compute the
        // POST-receipt value: (preQty + receiptQty) × newAvg, so the row is consistent once
        // the qty increment lands. This is the key fix for the per-location recompute.
        final Long receivingRowId = receivingRow.getId();
        for (StockOnHand row : allRows) {
            BigDecimal effectiveQty = row.getId().equals(receivingRowId)
                    ? row.getQuantity().add(receiptQty)   // post-receipt qty for receiving row
                    : row.getQuantity();                   // pre-receipt (stable) for all others
            BigDecimal rowNewValue = round4(effectiveQty.multiply(newAvg));
            row.applyCostRecompute(newAvg, rowNewValue, null);
            onHands.save(row);
        }

        // If the receiving row is newly created (not yet in allRows at the time of the
        // findByCompanyIdAndProductId call — the upsert save may not be visible yet),
        // apply avg_cost + the post-receipt value directly.
        if (allRows.stream().noneMatch(r -> r.getId().equals(receivingRowId))) {
            BigDecimal rowNewValue = round4(receiptQty.multiply(newAvg));
            receivingRow.applyCostRecompute(newAvg, rowNewValue, null);
            onHands.save(receivingRow);
        }

        return receiptValue;
    }

    // -------------------------------------------------------------------------
    // (b) Cost-issue: debit on_hand_value at current avg (ADR-0020 D-4b)
    //     ADR-0028 D-2 FIX (Finding 2): avg_cost is read from the company-product aggregate,
    //     not the single branch-row, so the issue costs at the true company-wide avg.
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

    /**
     * ADR-0028 D-2 FIX (Finding 2): resolves avg_cost from all location rows for (company, product)
     * so that an issue from LOC1 when stock also sits at LOC2 costs at the true company-product avg,
     * not an isolated per-location avg. Only the specific branch row's on_hand_value is decremented
     * (the qty delta is applied by the posting service to that row only).
     */
    private BigDecimal doCostIssue(Long companyId, Long branchId, Long productId,
                                    BigDecimal issuedQty) {
        // FIX G: zero issued qty — skip COGS leg, no division
        if (issuedQty == null || issuedQty.compareTo(BigDecimal.ZERO) == 0) {
            log.warn("InventoryValuation: costIssue — issuedQty is zero for company={} product={} " +
                             "— COGS leg skipped (FIX G div-guard)", companyId, productId);
            return null;
        }

        // Derive company-product avg from ALL location rows (ADR-0028 D-2).
        // All rows for the same company-product should carry the same avg_cost (synced on receipt).
        // Read any non-null avg_cost as the authoritative company-product avg.
        List<StockOnHand> allRows = onHands.findByCompanyIdAndProductId(companyId, productId);
        BigDecimal companyAvgCost = allRows.stream()
                .filter(r -> r.getAvgCost() != null)
                .map(StockOnHand::getAvgCost)
                .findFirst()
                .orElse(null);

        if (companyAvgCost == null) {
            log.warn("InventoryValuation: costIssue — avg_cost IS NULL for company={} product={} " +
                             "— COGS leg skipped (D-2 edge)", companyId, productId);
            return null;  // caller skips GL leg + logs anomaly
        }

        // Locate the issuing branch row to deduct on_hand_value from. ADR-0028 D-2/D-3 FIX: the qty
        // leg lands on the branch DEFAULT location (StockPostingServiceImpl resolves null → default),
        // so the value decrement must target that SAME row. The deprecated single-(company,branch,
        // product) finder throws NonUniqueResultException once the product has a second location row
        // in the branch, so use the location-aware finder symmetric with doReverseIssue.
        Long locId = locationResolver.defaultLocationId(companyId, branchId);
        StockOnHand soh = onHands
                .findByCompanyIdAndBranchIdAndLocationIdAndProductId(companyId, branchId, locId, productId)
                .orElse(null);

        if (soh == null) {
            log.warn("InventoryValuation: costIssue — no on-hand row for company={} branch={} product={} " +
                             "— COGS value computed but on_hand_value not decremented", companyId, branchId, productId);
            // Still return the issued value so the GL leg fires (quantity is moving via posting service)
            return round4(issuedQty.multiply(companyAvgCost));
        }

        BigDecimal issuedValue = round4(issuedQty.multiply(companyAvgCost));
        BigDecimal newValue    = soh.getOnHandValue().subtract(issuedValue);
        // avg_cost is unchanged on issue (BR-INV-01)
        soh.applyCostRecompute(companyAvgCost, newValue, null);
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
        // ADR-0028 D-2/D-3 FIX: the original issue's qty leg landed on the branch DEFAULT location
        // (StockPostingServiceImpl resolves a null locationId to defaultLocationId), and the qty
        // reversal lands there too. Restore on_hand_value to that SAME location row. The deprecated
        // single-(company,branch,product) finder throws NonUniqueResultException once a second
        // location row exists for the product in the branch (e.g. an in-branch transfer to a
        // non-default location), poisoning the SALE.VOIDED / DELIVERY.RETURNED handler. avg_cost is
        // NOT recomputed — an issue never moved it and a reversal does not either (D-5), so we do
        // NOT aggregate like reverseReceipt; we write the one location row the qty leg touched.
        Long locId = locationResolver.defaultLocationId(companyId, branchId);
        StockOnHand soh = onHands
                .findByCompanyIdAndBranchIdAndLocationIdAndProductId(companyId, branchId, locId, productId)
                .orElse(null);
        if (soh == null) return; // no on-hand row — cannot restore (shouldn't happen)

        BigDecimal newValue = soh.getOnHandValue().add(originalValue);
        // avg_cost unchanged — an issue never moved it; reversal also does not (D-5)
        soh.applyCostRecompute(soh.getAvgCost(), newValue, null);
        onHands.save(soh);
    }

    // -------------------------------------------------------------------------
    // (d) Reverse receipt: back out on_hand_value + recompute avg (ADR-0020 D-5)
    //     ADR-0028 D-2 FIX: aggregate ALL location rows, compute new avg on post-reversal totals,
    //     sync to ALL rows (mirrors doRecomputeOnReceipt).
    //     FIX A: postReversalQty derived from aggregate total minus original qty.
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

    /**
     * ADR-0028 D-2 FIX: aggregates ALL location rows to compute the post-reversal company-product
     * avg, then syncs to all rows. FIX A: soh rows hold PRE-reversal quantities at call time
     * (posting applies the delta after this returns), so postReversalQty = Σ qty − original qty.
     */
    private void doReverseReceipt(Long companyId, Long branchId, Long productId,
                                   BigDecimal originalQty, BigDecimal originalValue) {
        List<StockOnHand> allRows = onHands.findByCompanyIdAndProductId(companyId, productId);
        if (allRows.isEmpty()) return;

        // Aggregate PRE-reversal totals across all locations.
        BigDecimal totalQty   = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (StockOnHand row : allRows) {
            totalQty   = totalQty.add(row.getQuantity());
            totalValue = totalValue.add(row.getOnHandValue() != null ? row.getOnHandValue() : BigDecimal.ZERO);
        }

        // FIX A: posting service applies the negative qty delta AFTER this method is called.
        // PRE-reversal totals are used; subtract the original qty to derive POST-reversal total qty.
        BigDecimal postReversalTotalQty   = totalQty.subtract(originalQty.abs());
        BigDecimal postReversalTotalValue = totalValue.subtract(originalValue);

        BigDecimal newAvg;
        if (postReversalTotalQty.compareTo(BigDecimal.ZERO) > 0) {
            newAvg = round4(postReversalTotalValue.divide(postReversalTotalQty, SCALE, RM));
        } else {
            // Aggregate went to <= 0 after reversal — keep last known avg (D-5)
            newAvg = allRows.stream().filter(r -> r.getAvgCost() != null)
                    .map(StockOnHand::getAvgCost).findFirst().orElse(BigDecimal.ZERO);
        }

        // Sync new avg and re-attributed on_hand_value to every location row.
        // When postReversalTotalQty > 0: distribute postReversalTotalValue by row proportion
        // (row.qty × newAvg is equivalent since newAvg = postReversalTotalValue / postReversalTotalQty
        //  and the rows not receiving the reversal still carry their pre-reversal qty, which is correct).
        // When postReversalTotalQty <= 0: the aggregate value after reversal is postReversalTotalValue
        // (nominally 0 for a full reversal). Set every row to that value (clamped ≥ 0) — do NOT
        // multiply row.qty × newAvg because row.qty is still the PRE-reversal qty, giving a spurious
        // positive value for a full reversal where the correct on_hand_value is zero (FIX A).
        final boolean valueDepleted = postReversalTotalQty.compareTo(BigDecimal.ZERO) <= 0;
        for (StockOnHand row : allRows) {
            BigDecimal rowNewValue = valueDepleted
                    ? postReversalTotalValue.max(BigDecimal.ZERO)
                    : round4(row.getQuantity().multiply(newAvg));
            row.applyCostRecompute(newAvg, rowNewValue, null);
            onHands.save(row);
        }
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
                    "This product already has an opening valuation recorded. "
                            + "Opening valuation can only be set once per product.");
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
    //     ADR-0028 D-2: soh passed in from caller — applies to the specific location row.
    //     FIX D: public wrapper retries once on optimistic lock clash.
    //     FIX (retry re-read): use location-aware finder to re-read the SAME location row.
    // -------------------------------------------------------------------------

    @Override
    public void revalueAdjustment(String movementUid, StockOnHand soh, BigDecimal adjustQty,
                                   LocalDate postingDate,
                                   Long costCentreValueId, Long departmentValueId,
                                   String productCode, String reasonCode) {
        try {
            doRevalueAdjustment(movementUid, soh, adjustQty, postingDate,
                    costCentreValueId, departmentValueId, productCode, reasonCode);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.debug("InventoryValuation: optimistic lock clash on revalueAdjustment " +
                              "company={} movement={} — retrying once (FIX D / NFR-INV-05)",
                    soh.getCompanyId(), movementUid);
            // ADR-0028 D-2 FIX: re-read by LOCATION (not just company/branch/product) so the
            // retry operates on the exact same row — not any other location's row for the same product.
            StockOnHand freshSoh = (soh.getLocationId() != null)
                    ? onHands.findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                            soh.getCompanyId(), soh.getBranchId(), soh.getLocationId(), soh.getProductId())
                            .orElse(soh)
                    : onHands.findByCompanyIdAndBranchIdAndProductId(
                            soh.getCompanyId(), soh.getBranchId(), soh.getProductId()).orElse(soh);
            doRevalueAdjustment(movementUid, freshSoh, adjustQty, postingDate,
                    costCentreValueId, departmentValueId, productCode, reasonCode);
        }
    }

    private void doRevalueAdjustment(String movementUid, StockOnHand soh, BigDecimal adjustQty,
                                      LocalDate postingDate,
                                      Long costCentreValueId, Long departmentValueId,
                                      String productCode, String reasonCode) {
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
        // FOLLOW-001: pass productCode + reasonCode so memo text is human-readable, not a ULID.
        glPoster.postAdjustmentDirect(
                soh.getCompanyId(), soh.getBranchId(), postingDate,
                new InventoryGlPoster.AdjustmentPostCmd(
                        movementUid, BASE_CURRENCY, value, decrease,
                        actorId(RequestContext.get()),
                        costCentreValueId, departmentValueId,
                        productCode, reasonCode));
    }

    // -------------------------------------------------------------------------
    // (g) Apply landed cost: add allocation amount to on_hand_value, recompute avg (ADR-0027 D-5)
    //     REQUIRES MANDATORY — called from LandedCostStockHandler in its REQUIRED TX.
    //     FIX D pattern: retry once on optimistic lock clash.
    // -------------------------------------------------------------------------

    @Override
    public void applyLandedCost(Long companyId, Long branchId, Long productId,
                                 BigDecimal allocatedAmount) {
        // ADR-0028 D-2 FIX: landed cost capitalises into the company-product moving average across
        // ALL location rows, so branchId is no longer needed below (the allocation is company-wide).
        try {
            doApplyLandedCost(companyId, productId, allocatedAmount);
        } catch (ObjectOptimisticLockingFailureException ex) {
            log.debug("InventoryValuation: optimistic lock clash on applyLandedCost " + RETRY_MSG,
                    companyId, productId);
            doApplyLandedCost(companyId, productId, allocatedAmount);
        }
    }

    private void doApplyLandedCost(Long companyId, Long productId,
                                    BigDecimal allocatedAmount) {
        if (allocatedAmount == null || allocatedAmount.compareTo(BigDecimal.ZERO) == 0) {
            return;
        }

        // ADR-0028 D-2 FIX: landed cost capitalises into the company-product moving average and so
        // MUST be applied across ALL location rows (mirrors doRecomputeOnReceipt), not a single
        // branch row. The deprecated single-(company,branch,product) finder also throws
        // NonUniqueResultException once the product has 2+ location rows in the branch, rolling back
        // the landed-cost handler TX. Aggregate Σqty/Σvalue, derive one company-product avg, and
        // sync the avg + re-attributed (row.qty × avg) value to every location row.
        List<StockOnHand> allRows = onHands.findByCompanyIdAndProductId(companyId, productId);
        if (allRows.isEmpty()) {
            log.warn("InventoryValuation: applyLandedCost — no on-hand rows for company={} product={} " +
                             "— landed cost delta not applied", companyId, productId);
            return;
        }

        BigDecimal totalQty   = BigDecimal.ZERO;
        BigDecimal totalValue = BigDecimal.ZERO;
        for (StockOnHand row : allRows) {
            totalQty   = totalQty.add(row.getQuantity());
            totalValue = totalValue.add(row.getOnHandValue() != null ? row.getOnHandValue() : BigDecimal.ZERO);
        }

        BigDecimal newTotalValue = totalValue.add(allocatedAmount);

        // Recompute the company-product avg = new_total_value / Σqty (guard against zero/null qty:
        // keep the prior avg if the aggregate qty is non-positive — value still capitalised below).
        BigDecimal priorAvg = allRows.stream().filter(r -> r.getAvgCost() != null)
                .map(StockOnHand::getAvgCost).findFirst().orElse(BigDecimal.ZERO);
        BigDecimal newAvg = priorAvg;
        if (totalQty.compareTo(BigDecimal.ZERO) > 0) {
            newAvg = round4(newTotalValue.divide(totalQty, SCALE, RM));
        }

        // Sync new avg + re-attributed on_hand_value (row.qty × newAvg) to EVERY location row.
        // Σ (row.qty × newAvg) == totalQty × newAvg == newTotalValue (when totalQty > 0), so the
        // Σ on_hand_value == GL 1300 invariant holds (the handler posts the same allocatedAmount to GL).
        for (StockOnHand row : allRows) {
            BigDecimal rowNewValue = round4(row.getQuantity().multiply(newAvg));
            row.applyCostRecompute(newAvg, rowNewValue, null);
            onHands.save(row);
        }
    }

    // -------------------------------------------------------------------------
    // (h) Transfer cost: move on_hand_value from source to destination location
    //     Called by StockTransferServiceImpl (instant) and the dispatch/receive handlers
    //     (in-transit). ADR-0028 D-5 / issue #12 fix.
    // -------------------------------------------------------------------------

    @Override
    public void transferCost(Long companyId,
                             Long srcBranchId, Long srcLocationId,
                             Long destBranchId, Long destLocationId,
                             Long productId, BigDecimal qty) {
        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) return;

        // --- source row: debit on_hand_value by qty × srcAvgCost ----------------
        StockOnHand srcSoh = onHands
                .findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                        companyId, srcBranchId, srcLocationId, productId)
                .orElse(null);

        BigDecimal srcAvgCost = srcSoh != null ? srcSoh.getAvgCost() : null;
        if (srcAvgCost == null) {
            // No cost established at source — nothing to move (D-2 edge).
            log.warn("InventoryValuation: transferCost — srcAvgCost IS NULL for company={} " +
                             "srcLoc={} product={} — value transfer skipped", companyId, srcLocationId, productId);
            return;
        }

        BigDecimal transferValue = round4(qty.multiply(srcAvgCost));

        if (srcSoh != null) {
            BigDecimal newSrcValue = srcSoh.getOnHandValue().subtract(transferValue);
            // avg_cost at source stays the same (qty depletes, avg unchanged — mirrors costIssue)
            srcSoh.applyCostRecompute(srcAvgCost, newSrcValue, null);
            onHands.save(srcSoh);
        }

        // --- destination row: credit on_hand_value + recompute avgCost ----------
        // Upsert the dest row (may not exist yet if the product has never been at this location).
        StockOnHand destSoh = onHands
                .findByCompanyIdAndBranchIdAndLocationIdAndProductId(
                        companyId, destBranchId, destLocationId, productId)
                .orElseGet(() -> {
                    StockOnHand fresh = new StockOnHand(companyId, destBranchId, destLocationId, productId);
                    return onHands.save(fresh);
                });

        BigDecimal destPreQty   = destSoh.getQuantity();
        BigDecimal destPreValue = destSoh.getOnHandValue() != null ? destSoh.getOnHandValue() : BigDecimal.ZERO;

        // Post-transfer qty on the dest row (the posting service applies the qty delta AFTER this
        // method returns, so current qty is pre-transfer; add qty to get post-transfer total).
        BigDecimal destPostQty   = destPreQty.add(qty);
        BigDecimal destPostValue = destPreValue.add(transferValue);

        BigDecimal newDestAvg;
        if (destPostQty.compareTo(BigDecimal.ZERO) > 0) {
            newDestAvg = round4(destPostValue.divide(destPostQty, SCALE, RM));
        } else {
            // Destination qty will be zero or negative after transfer (edge case) — keep srcAvgCost
            newDestAvg = destSoh.getAvgCost() != null ? destSoh.getAvgCost() : srcAvgCost;
        }

        destSoh.applyCostRecompute(newDestAvg, destPostValue, null);
        onHands.save(destSoh);
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
