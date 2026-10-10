package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.BillCostVariancePayload;
import com.erp.modules.ap.domain.dto.BillMatchResultDto;
import com.erp.modules.ap.domain.dto.BillMatchResultDto.LineMatchDto;
import com.erp.modules.ap.domain.entity.BillMatch;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.domain.enums.BillMatchStatus;
import com.erp.modules.ap.domain.enums.BillMatchType;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.BillMatchRepository;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.JournalEntry;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.purchases.domain.dto.GoodsReceiptLineDto;
import com.erp.modules.purchases.domain.dto.PurchaseOrderLineDto;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.FxDocumentConverter;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 3-way match engine + synchronous GL posting on match (ADR-0015 D-3/D-4/D-6).
 *
 * <p>Per each bill line with po_line_uid/gr_line_uid:
 * <ul>
 *   <li>Price: |bill unit_cost − PO unit_cost| within tolerance (2% or abs, whichever greater).
 *   <li>Qty: billed_qty ≤ gr received_qty − qty already billed on other bills for the same
 *       receipt line, both in the receipt line's unit (exact by default — over-billing held).
 * </ul>
 * All lines within tolerance → bill MATCHED, posts DR Purchases / CR AP-control (D-6).
 * Any over-tolerance → bill HELD, nothing posts.
 * GL failure (missing config, closed period) rolls back the whole command (D-4).
 *
 * <p><b>The control fails CLOSED.</b> A line that references a purchase order or a goods receipt
 * but whose order/receipt line cannot be resolved is HELD, never MATCHED — the comparison did not
 * run, so nothing may post on the strength of it. Only a line with no purchase link at all (a
 * service charge) passes without a comparison, and it says so: {@code comparisonPerformed=false}
 * with every variance reported as NULL rather than a reassuring 0.
 */
@Service
@Transactional
public class BillMatchServiceImpl implements BillMatchService {

    /** Default tolerance: 2% of PO cost, applied when no ap_settings row exists. */
    private static final BigDecimal DEFAULT_TOLERANCE_PCT = new BigDecimal("2.00");
    private static final BigDecimal HUNDRED               = new BigDecimal("100");

    private final SupplierBillRepository     bills;
    private final SupplierBillLineRepository lines;
    private final BillMatchRepository        matches;
    private final PurchaseMatchReader        purchaseReader;
    private final GLPostingService           glPosting;
    private final GLConfigResolver           glConfig;
    private final JournalEntryRepository     journalEntries;   // FIX H: idempotency guard
    private final ApBillNumberGenerator      numbers;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;
    private final JdbcTemplate               jdbc;
    /** ADR-0036 D-3: converts face amounts to base before LineDraft construction. */
    private final FxDocumentConverter        fxConverter;
    /** ACC-17: carries the on-hand share of a bill-vs-receipt cost difference to the stock module. */
    private final OutboxPublisher            outbox;

    public BillMatchServiceImpl(SupplierBillRepository bills,
                                 SupplierBillLineRepository lines,
                                 BillMatchRepository matches,
                                 PurchaseMatchReader purchaseReader,
                                 GLPostingService glPosting,
                                 GLConfigResolver glConfig,
                                 JournalEntryRepository journalEntries,
                                 ApBillNumberGenerator numbers,
                                 ScopeGuard scopeGuard,
                                 AuditService audit,
                                 JdbcTemplate jdbc,
                                 FxDocumentConverter fxConverter,
                                 OutboxPublisher outbox) {
        this.bills          = bills;
        this.lines          = lines;
        this.matches        = matches;
        this.purchaseReader = purchaseReader;
        this.glPosting      = glPosting;
        this.glConfig       = glConfig;
        this.journalEntries = journalEntries;
        this.numbers        = numbers;
        this.scopeGuard     = scopeGuard;
        this.audit          = audit;
        this.jdbc           = jdbc;
        this.fxConverter    = fxConverter;
        this.outbox         = outbox;
    }

    @Override
    public BillMatchResultDto runMatch(String billUid) {
        SupplierBill bill = Lookups.orNotFound(bills.findByUid(billUid), "SupplierBill", billUid);
        scopeGuard.assertCanActIn(RequestContext.get(), bill.getCompanyId());

        if (bill.getStatus() != SupplierBillStatus.DRAFT
                && bill.getStatus() != SupplierBillStatus.HELD) {
            // billUid intentionally not surfaced in the user message (error-hygiene rule)
            throw new IllegalStateException(
                    "This bill is in status " + bill.getStatus()
                            + " — can only match DRAFT or HELD bills.");
        }

        List<SupplierBillLine> billLines = lines.findBySupplierBillIdOrderByLineNo(bill.getId());
        BigDecimal tolerancePct = loadTolerancePct(bill.getCompanyId());
        BigDecimal toleranceAbs = BigDecimal.ZERO;

        List<LineMatchDto> lineResults = new ArrayList<>();
        boolean anyHeld = false;

        for (SupplierBillLine line : billLines) {
            LineVerdict verdict = evaluate(bill, line, tolerancePct, toleranceAbs);
            if (verdict.status() != BillMatchStatus.MATCHED) {
                anyHeld = true;
            }

            // Upsert bill_match row (one per line — uq_bill_match_line)
            BillMatch match = matches.findBySupplierBillLineId(line.getId())
                    .orElse(null);
            // P2 D7: a GR-linked line is a 3-way match; a PO-only (no GR) line is 2-way.
            BillMatchType matchType = (line.getGrLineUid() != null)
                    ? BillMatchType.THREE_WAY
                    : BillMatchType.TWO_WAY;
            if (match == null) {
                match = new BillMatch(
                        bill.getCompanyId(), bill.getId(), line.getId(),
                        verdict.poUnitCost(), verdict.grReceivedQty(), line.getBilledQty(),
                        verdict.priceVariance(), verdict.priceVariancePct(), verdict.qtyVariance(),
                        verdict.status(), tolerancePct, toleranceAbs, actorId());
                match.setMatchType(matchType);
            } else {
                // Re-match: overwrite the FACTS too, not just the status. Leaving the previous
                // run's po_unit_cost / variances behind makes the row claim a comparison that
                // this run did not make (the variance columns are NOT NULL, so an un-compared
                // leg persists as 0 — the nullable po_unit_cost / gr_received_qty are what tell
                // a reviewer whether the check actually ran).
                match.setMatchStatus(verdict.status());
                match.setMatchType(matchType);
                match.setPoUnitCostAmount(verdict.poUnitCost());
                match.setGrReceivedQty(verdict.grReceivedQty());
                match.setBilledQty(line.getBilledQty());
                match.setPriceVarianceAmount(zeroIfNull(verdict.priceVariance()));
                match.setPriceVariancePct(zeroIfNull(verdict.priceVariancePct()));
                match.setQtyVariance(zeroIfNull(verdict.qtyVariance()));
                match.setMatchedAt(Instant.now());
            }
            // Durable evidence of WHY a line is on hold (cleared when a re-run resolves it).
            match.setVarianceReason(verdict.reason());
            match = matches.save(match);

            lineResults.add(new LineMatchDto(
                    line.getId(), line.getUid(), verdict.status(),
                    verdict.priceVariance(), verdict.priceVariancePct(), verdict.qtyVariance(),
                    verdict.poUnitCost(), verdict.grReceivedQty(), line.getBilledQty(),
                    match.getMatchedAt(),
                    verdict.comparisonPerformed(), verdict.note()));
        }

        // Update bill status
        SupplierBillStatus newBillStatus = anyHeld
                ? SupplierBillStatus.HELD
                : SupplierBillStatus.MATCHED;
        // Assign bill_number BEFORE the status transition (issue #3 / finding #15). Order matters:
        // setStatus dirties the bill as non-DRAFT, and numbers.nextBill(...) runs a code_sequence
        // query that triggers a Hibernate autoflush — flushing a non-DRAFT-but-unnumbered row
        // violates chk_supplier_bill_number_when_posted (which requires bill_number IS NOT NULL for
        // any status != DRAFT). This covers MATCHED *and* HELD transitions. Guard is idempotent
        // (no-op if the bill was already numbered on a prior runMatch call for a re-run HELD bill).
        if (bill.getBillNumber() == null) {
            bill.setBillNumber(numbers.nextBill(bill.getCompanyId()));
        }

        bill.setStatus(newBillStatus);

        if (!anyHeld) {
            postMatchedBillToGl(bill);
        }

        bill = bills.save(bill);

        audit.record(AuditEvent.of(AuditActions.AP_BILL_MATCH, "supplier_bills",
                        bill.getId(), bill.getUid())
                .detail(Map.of("matchResult", newBillStatus.name(), "lineCount",
                        String.valueOf(billLines.size()))));

        return new BillMatchResultDto(bill.getUid(), newBillStatus, lineResults);
    }

    @Override
    public BillMatchResultDto acceptVariance(String billUid, String billLineUid) {
        SupplierBill bill = Lookups.orNotFound(bills.findByUid(billUid), "SupplierBill", billUid);
        scopeGuard.assertCanActIn(RequestContext.get(), bill.getCompanyId());

        if (bill.getStatus() != SupplierBillStatus.HELD) {
            throw new IllegalStateException("This bill is not HELD.");
        }

        SupplierBillLine line = lines.findBySupplierBillIdAndUid(bill.getId(), billLineUid)
                .orElseThrow(() -> new NotFoundException("Bill line not found."));

        BillMatch match = matches.findBySupplierBillLineId(line.getId())
                .orElseThrow(() -> new NotFoundException(
                        "No match record found for this bill line."));

        match.setMatchStatus(BillMatchStatus.VARIANCE_ACCEPTED);
        match.setAcceptedBy(actorId());
        match.setAcceptedAt(Instant.now());
        match.setMatchedAt(Instant.now());
        matches.save(match);

        // Check if ALL lines are now MATCHED or VARIANCE_ACCEPTED
        List<BillMatch> allMatches = matches.findBySupplierBillId(bill.getId());
        boolean allResolved = allMatches.stream()
                .allMatch(m -> m.getMatchStatus() == BillMatchStatus.MATCHED
                            || m.getMatchStatus() == BillMatchStatus.VARIANCE_ACCEPTED);

        if (allResolved) {
            // Assign bill_number before MATCHED transition (finding #15):
            // idempotent — acceptVariance may be called multiple times (once per held line).
            if (bill.getBillNumber() == null) {
                bill.setBillNumber(numbers.nextBill(bill.getCompanyId()));
            }
            bill.setStatus(SupplierBillStatus.MATCHED);
            postMatchedBillToGl(bill);
            bills.save(bill);
        }

        audit.record(AuditEvent.of(AuditActions.AP_BILL_MATCH, "supplier_bills",
                        bill.getId(), bill.getUid())
                .detail(Map.of("action", "acceptVariance", "billLineUid", billLineUid)));

        List<LineMatchDto> lineResults = allMatches.stream().map(m -> {
            // Company-scoped, from the LOADED bill — the lines of a bill always share its company,
            // so this is the same row for every legitimate call and closes the confused-deputy hole.
            SupplierBillLine l = lines
                    .findByCompanyIdAndId(bill.getCompanyId(), m.getSupplierBillLineId())
                    .orElse(null);
            String lUid = l != null ? l.getUid() : null;
            // A NULL po_unit_cost / gr_received_qty means that leg never ran, so its stored
            // variance is a 0 default, not a result — publish NULL rather than a clean-looking 0.
            boolean priceChecked = m.getPoUnitCostAmount() != null;
            boolean qtyChecked   = m.getGrReceivedQty() != null;
            return new LineMatchDto(m.getSupplierBillLineId(), lUid, m.getMatchStatus(),
                    priceChecked ? m.getPriceVarianceAmount() : null,
                    priceChecked ? m.getPriceVariancePct() : null,
                    qtyChecked ? m.getQtyVariance() : null,
                    m.getPoUnitCostAmount(), m.getGrReceivedQty(), m.getBilledQty(),
                    m.getMatchedAt(),
                    priceChecked && qtyChecked, m.getVarianceReason());
        }).toList();

        return new BillMatchResultDto(bill.getUid(), bill.getStatus(), lineResults);
    }

    // -------------------------------------------------------------------------
    // The control itself
    // -------------------------------------------------------------------------

    /**
     * One line's verdict: the status, the facts that were <em>actually</em> compared, and the
     * plain-English note for the accountant.
     *
     * <p>A variance is NULL when that leg did not run — the caller must never publish a 0 for a
     * comparison that never happened, and the persisted row keeps po_unit_cost / gr_received_qty
     * NULL for the same reason. {@code reason} is the short (≤100 char) form stored on the row.
     */
    private record LineVerdict(
            BillMatchStatus status,
            BigDecimal priceVariance,
            BigDecimal priceVariancePct,
            BigDecimal qtyVariance,
            BigDecimal poUnitCost,
            BigDecimal grReceivedQty,
            boolean comparisonPerformed,
            String note,
            String reason) {}

    private static final String NO_PURCHASE_LINK_NOTE =
            "No purchase order or goods receipt is linked to this line, so there was nothing to "
            + "check it against. It was accepted as a service charge.";
    private static final String NO_LINKS_NOTE =
            "This line is not linked to a purchase order line or a goods receipt line, so neither "
            + "the price nor the quantity billed could be checked. Link it to the order line and "
            + "the goods receipt line, then run the match again.";
    private static final String NO_PO_LINE_NOTE =
            "This line is not linked to a purchase order line, so the price billed could not be "
            + "checked against the order. Link it to the order line, then run the match again.";
    private static final String NO_GR_LINE_NOTE =
            "The goods receipt for this line could not be found, so the quantity billed could not "
            + "be checked against what was received. Attach the goods receipt line to this bill "
            + "line, then run the match again.";
    private static final String PRICE_ALSO_OFF_NOTE =
            " The price billed also differs from the purchase order price — check the invoice "
            + "against the order before accepting it.";
    private static final String PRICE_VARIANCE_NOTE =
            "The price billed differs from the purchase order price by more than the allowed "
            + "tolerance. Check the invoice against the order, then either correct the bill or "
            + "accept the variance.";
    private static final String QTY_VARIANCE_NOTE =
            "More is billed than was received on the goods receipt. Check the receipt, then "
            + "either correct the bill or accept the variance.";

    /**
     * Decides one line, failing CLOSED.
     *
     * <p>Three outcomes, and the difference between the last two is the whole point:
     * <ul>
     *   <li><b>No purchase link at all</b> (no order on the bill, no order/receipt line on the
     *       line) — a genuine service charge. Passes, but flagged as not compared.
     *   <li><b>Linked to a purchase but the order/receipt line could not be resolved</b> — the
     *       control did NOT run, so the line is HELD. It used to be stamped MATCHED: live UAT saw
     *       a 15 × 9,999,999 bill sail through against a 20 × 4,500 order line (both facts NULL,
     *       both variances 0) and auto-post to the GL.
     *   <li><b>Fully resolved</b> — the original price/qty comparison, unchanged.
     * </ul>
     */
    private LineVerdict evaluate(SupplierBill bill, SupplierBillLine line,
                                 BigDecimal tolerancePct, BigDecimal toleranceAbs) {
        boolean purchaseLinked = bill.getPurchaseOrderUid() != null
                || line.getPoLineUid() != null
                || line.getGrLineUid() != null;

        if (!purchaseLinked) {
            return new LineVerdict(BillMatchStatus.MATCHED, null, null, null, null, null,
                    false, NO_PURCHASE_LINK_NOTE, null);
        }

        Optional<PurchaseOrderLineDto> poLineOpt =
                (bill.getPurchaseOrderUid() != null && line.getPoLineUid() != null)
                        ? purchaseReader.findPoLine(bill.getPurchaseOrderUid(), line.getPoLineUid())
                        : Optional.empty();
        // Resolve the GR line from its uid alone — the bill line carries no GR header ref (D-11).
        Optional<GoodsReceiptLineDto> grLineOpt =
                findGrLineByUid(bill.getCompanyId(), line.getGrLineUid());

        BigDecimal poUnitCost    = poLineOpt.map(PurchaseOrderLineDto::unitCostAmount).orElse(null);
        // AP-06: compare like with like. The bill line's quantity is in the order/receipt unit — the
        // unit its price is checked in (PO unit cost) and the unit the receipt picker shows the clerk
        // ("received 10 Carton"). The receipt line's receivedQty is in that same unit (a receipt line
        // always takes its order line's unit); qtyInBase is in pieces/bottles. Comparing billed
        // cartons against received bottles let a bill for 200 cartons pass against 240 bottles.
        BigDecimal grReceivedQty = grLineOpt.map(BillMatchServiceImpl::receivedInLineUnit).orElse(null);
        // AP-05: what other bills already claim against the same receipt line. Without this the same
        // GRN line could be billed twice under two invoice numbers and both bills would post.
        BigDecimal alreadyBilled = grReceivedQty != null
                ? billedElsewhere(bill, line.getGrLineUid())
                : BigDecimal.ZERO;

        // Price leg — computable whenever the ORDER line resolved, even if the receipt did not.
        // Worth computing in that case: it puts the real numbers in front of the reviewer.
        BigDecimal priceVar = null;
        BigDecimal priceVarPct = null;
        boolean priceOverTolerance = false;
        if (poUnitCost != null) {
            priceVar = line.getUnitCostAmount().subtract(poUnitCost);
            BigDecimal absPriceVar = priceVar.abs();
            priceVarPct = BigDecimal.ZERO;
            if (poUnitCost.compareTo(BigDecimal.ZERO) > 0) {
                priceVarPct = absPriceVar
                        .divide(poUnitCost, 6, RoundingMode.HALF_UP)
                        .multiply(HUNDRED);
            }
            BigDecimal allowedAbs = poUnitCost
                    .multiply(tolerancePct)
                    .divide(HUNDRED, 4, RoundingMode.HALF_UP)
                    .max(toleranceAbs);
            priceOverTolerance = absPriceVar.compareTo(allowedAbs) > 0;
        }

        // Qty leg — only the goods receipt can say how much actually arrived.
        // The quantity still open on the receipt is what was received less what other bills claim.
        BigDecimal qtyVar = null;
        boolean overBilled = false;
        boolean billedElsewhere = alreadyBilled.signum() > 0;
        if (grReceivedQty != null) {
            BigDecimal stillUnbilled = grReceivedQty.subtract(alreadyBilled);
            qtyVar = line.getBilledQty().subtract(stillUnbilled);
            overBilled = qtyVar.signum() > 0;
        }

        if (poUnitCost == null || grReceivedQty == null) {
            // FAIL CLOSED — a leg of the control could not run. Hold on the leg that is missing
            // (price when the order is unknown, quantity when the receipt is), and report the leg
            // that did run so the reviewer sees the real figures.
            BillMatchStatus status = (poUnitCost == null || priceOverTolerance)
                    ? BillMatchStatus.HELD_PRICE_VARIANCE
                    : BillMatchStatus.HELD_QTY_VARIANCE;
            String note;
            String reason;
            if (poUnitCost == null && grReceivedQty == null) {
                note   = NO_LINKS_NOTE;
                reason = "Order line and goods receipt line not linked — nothing was checked.";
            } else if (poUnitCost == null) {
                note   = NO_PO_LINE_NOTE;
                reason = "Purchase order line not linked — price not checked.";
            } else {
                note   = NO_GR_LINE_NOTE + (priceOverTolerance ? PRICE_ALSO_OFF_NOTE : "");
                reason = "Goods receipt line not found — quantity received not checked.";
            }
            return new LineVerdict(status, priceVar, priceVarPct, qtyVar,
                    poUnitCost, grReceivedQty, false, note, reason);
        }

        // Fully resolved — the real 3-way comparison.
        if (priceOverTolerance) {
            return new LineVerdict(BillMatchStatus.HELD_PRICE_VARIANCE,
                    priceVar, priceVarPct, qtyVar, poUnitCost, grReceivedQty,
                    true, PRICE_VARIANCE_NOTE, "Price above the agreed tolerance.");
        }
        if (overBilled) {
            if (billedElsewhere) {
                return new LineVerdict(BillMatchStatus.HELD_QTY_VARIANCE,
                        priceVar, priceVarPct, qtyVar, poUnitCost, grReceivedQty,
                        true, alreadyBilledNote(grReceivedQty, alreadyBilled),
                        "Receipt already billed on another bill.");
            }
            return new LineVerdict(BillMatchStatus.HELD_QTY_VARIANCE,
                    priceVar, priceVarPct, qtyVar, poUnitCost, grReceivedQty,
                    true, QTY_VARIANCE_NOTE, "Billed more than was received.");
        }
        return new LineVerdict(BillMatchStatus.MATCHED,
                priceVar, priceVarPct, qtyVar, poUnitCost, grReceivedQty,
                true, null, null);
    }

    private static BigDecimal zeroIfNull(BigDecimal v) {
        return v != null ? v : BigDecimal.ZERO;
    }

    /**
     * The receipt line's quantity in the receipt line's own unit — the unit the bill line is in.
     * {@code receivedQty} is always populated on a receipt line; should it ever be missing, the base
     * quantity is the only fact left and is used rather than reporting nothing was received.
     */
    static BigDecimal receivedInLineUnit(GoodsReceiptLineDto gr) {
        return gr.receivedQty() != null ? gr.receivedQty() : gr.qtyInBase();
    }

    /** Quantity of this receipt line already claimed by other (non-draft) bills; 0 when none. */
    private BigDecimal billedElsewhere(SupplierBill bill, String grLineUid) {
        if (grLineUid == null) {
            return BigDecimal.ZERO;
        }
        // A bill not yet saved has no id; -1 never matches a real row, so nothing is excluded.
        Long excludeId = bill.getId() != null ? bill.getId() : -1L;
        BigDecimal sum = lines.sumBilledQtyOnOtherBills(bill.getCompanyId(), grLineUid, excludeId);
        return sum != null ? sum : BigDecimal.ZERO;
    }

    private static String alreadyBilledNote(BigDecimal received, BigDecimal alreadyBilled) {
        BigDecimal open = received.subtract(alreadyBilled).max(BigDecimal.ZERO);
        return "Other bills already claim " + plain(alreadyBilled) + " of the " + plain(received)
                + " received on this goods receipt line, so only " + plain(open)
                + " is left to bill. Check whether this invoice was already entered, then either "
                + "correct the bill or accept the variance.";
    }

    private static String plain(BigDecimal v) {
        return v.stripTrailingZeros().toPlainString();
    }

    // -------------------------------------------------------------------------
    // GL posting (D-4/D-6/ADR-0020 D-9): DR GRNI (goods) + DR Purchases (service) [+ DR VAT] / CR AP
    // -------------------------------------------------------------------------

    /**
     * Post the matched bill to GL.
     *
     * <p>ADR-0020 D-9 GRNI swap: bill lines linked to a GR line ({@code gr_line_uid IS NOT NULL})
     * are goods lines — their net amount clears the GRNI liability accrued at goods receipt
     * (DR GRNI / CR AP). Lines with no GR link are service lines (DR PURCHASES / CR AP).
     * A bill can mix both types; the two buckets accumulate separately.
     *
     * <p>Finding #15 (bill_number) coexists independently — already handled in {@link #runMatch}.
     */
    private void postMatchedBillToGl(SupplierBill bill) {
        // Fail closed, independently of the caller: re-read the line verdicts and refuse to post
        // while any of them is on hold. Both callers already set MATCHED before getting here, so
        // this is the last line of defence — a bill that skipped the control must never reach the
        // ledger, and the UAT showed how easily a hole upstream turns into a posted JE.
        boolean anyUnresolved = matches.findBySupplierBillId(bill.getId()).stream()
                .anyMatch(m -> m.getMatchStatus() != BillMatchStatus.MATCHED
                        && m.getMatchStatus() != BillMatchStatus.VARIANCE_ACCEPTED);
        if (anyUnresolved) {
            throw new IllegalStateException(
                    "This bill still has lines on hold, so it cannot be posted. "
                            + "Resolve or accept each held line first.");
        }

        // FIX H (adversarial review): idempotency guard — if a journal entry already exists for
        // (companyId, AP_BILL, bill.uid) a previous run already posted; re-stamp the GL ref and
        // return without double-posting (the AR/AP precedent — ADR-0020 D-4 NFR-INV-04).
        JournalEntry existing = journalEntries
                .findByCompanyIdAndSourceTypeAndSourceRef(
                        bill.getCompanyId(), JournalSourceType.AP_BILL, bill.getUid())
                .orElse(null);
        if (existing != null) {
            bill.setPostedGlEntryUid(existing.getUid());
            audit.record(AuditEvent.of(AuditActions.AP_BILL_POST, "supplier_bills",
                            bill.getId(), bill.getUid())
                    .detail(Map.of("action", "idempotentSkip",
                            "glEntryUid", existing.getUid())));
            return;
        }

        List<SupplierBillLine> billLines =
                lines.findBySupplierBillIdOrderByLineNo(bill.getId());

        // ADR-0036 D-3: convert face amounts to BASE before LineDraft construction.
        // GL engine (GLPostingServiceImpl) is BYTE-UNTOUCHED; only base-currency lines reach it.
        // AP control leg (CR AP) is the BALANCING PLUG to absorb HALF_UP rounding residual. (D-3/D-8)
        String    docCurrency = bill.getCurrency().value();
        Long      companyId   = bill.getCompanyId();

        // Convert gross once — used for the D-4 triple stamp and plugScale below.
        ConvertedAmount grossConv = fxConverter.toBase(
                bill.getGrossAmount(), docCurrency, companyId, bill.getBillDate());

        // Resolve base currency code for LineDraft.currency (D-3: every line = base currency).
        // BillMatchServiceImpl injects JdbcTemplate; read once rather than adding CompanyRepository.
        String resolvedBaseCurrency = jdbc.queryForObject(
                "SELECT base_currency FROM companies WHERE id = ?", String.class, companyId);
        final String postCurrency = (resolvedBaseCurrency != null) ? resolvedBaseCurrency : "TZS";

        ChartOfAccount apAcct = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_PAYABLE);
        List<LineDraft> glLines = new ArrayList<>();

        // ADR-0025 D-6: only P&L-relevant legs carry the dimension tag. AP control leg untagged.
        Long ccId   = bill.getCostCentreValueId();
        Long deptId = bill.getDepartmentValueId();

        // Build debit legs: DR GRNI (goods) + DR Purchases (service) in BASE currency.
        // Accumulate converted base amounts; AP plug = sum of all debit base amounts.
        BigDecimal baseGoodsNet     = BigDecimal.ZERO;   // GRNI cleared, at the receipts' value
        BigDecimal baseServiceTotal = BigDecimal.ZERO;
        BigDecimal baseInventoryVariance = BigDecimal.ZERO;   // bill − receipt, goods on hand
        BigDecimal baseCogsVariance      = BigDecimal.ZERO;   // bill − receipt, goods gone
        List<BillCostVariancePayload.Line> revaluations = new ArrayList<>();

        for (SupplierBillLine l : billLines) {
            BigDecimal lineNet = l.getLineNetAmount() != null ? l.getLineNetAmount() : BigDecimal.ZERO;
            if (lineNet.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal baseLineNet = fxConverter.toBase(
                    lineNet, docCurrency, companyId, bill.getBillDate()).baseAmount();

            if (l.getGrLineUid() != null) {
                // Goods line. ACC-17 / LBO-13 / PUR-21: GRNI was credited at the RECEIPT's value
                // (GR cost, converted at the receipt date), so it must be cleared at that same value
                // — clearing it at the bill amount stranded every price or rate difference in GRNI
                // for good. The difference between what the supplier billed and what the receipt
                // booked is a cost correction: the share of the goods still on hand goes to
                // Inventory (and into the moving average, via the outbox), the share already sold
                // or used goes to COGS.
                ReceiptValue rv = receiptValueForBilledQty(companyId, l);
                if (rv == null) {
                    // Receipt line unreadable (never expected on a matched line) — keep the old
                    // behaviour rather than guess a value: clear GRNI at the bill amount.
                    baseGoodsNet = baseGoodsNet.add(baseLineNet);
                    continue;
                }
                baseGoodsNet = baseGoodsNet.add(rv.baseValue());
                BigDecimal variance = baseLineNet.subtract(rv.baseValue());
                if (variance.signum() != 0) {
                    BigDecimal invShare = onHandShare(companyId, rv, variance);
                    baseInventoryVariance = baseInventoryVariance.add(invShare);
                    baseCogsVariance = baseCogsVariance.add(variance.subtract(invShare));
                    if (invShare.signum() != 0) {
                        revaluations.add(new BillCostVariancePayload.Line(rv.productId(), invShare));
                    }
                }
            } else {
                // Service line: one LineDraft per line with project tag (ADR-0033 D-4b).
                // ADR-0040 D-8: debit the line's gl_account_id override when set, else the PURCHASES default.
                Long expenseAccountId = l.getGlAccountId() != null
                        ? l.getGlAccountId()
                        : glConfig.resolve(companyId, GlConfigKey.PURCHASES).getId();
                // ADR-0041 D4: thread the PER-LINE dimension tags onto the P&L leg this line generates,
                // so a supplier-bill-line cost-centre/department reaches the ledger. Fall back to the
                // bill HEADER dimension when the line is untagged (preserves the prior behaviour where
                // every service leg carried the header dimension — zero regression for untagged lines).
                Long lineCcId   = l.getCostCentreValueId() != null ? l.getCostCentreValueId() : ccId;
                Long lineDeptId = l.getDepartmentValueId() != null ? l.getDepartmentValueId() : deptId;
                glLines.add(new LineDraft(expenseAccountId,
                        baseLineNet, BigDecimal.ZERO,
                        postCurrency, "Purchases — " + bill.getSupplierInvoiceNo(),
                        lineCcId, lineDeptId, null, null,
                        l.getProjectId(), l.getProjectTaskId(), null));
                baseServiceTotal = baseServiceTotal.add(baseLineNet);
            }
        }

        if (baseGoodsNet.compareTo(BigDecimal.ZERO) > 0) {
            ChartOfAccount grniAcct = glConfig.resolve(companyId, GlConfigKey.GRNI);
            glLines.add(new LineDraft(grniAcct.getId(),
                    baseGoodsNet, BigDecimal.ZERO,
                    postCurrency, "GRNI clear — " + bill.getSupplierInvoiceNo()));
        }
        addVarianceLeg(glLines, companyId, GlConfigKey.INVENTORY, baseInventoryVariance,
                postCurrency, "Purchase cost difference (stock on hand) — " + bill.getSupplierInvoiceNo());
        addVarianceLeg(glLines, companyId, GlConfigKey.COGS, baseCogsVariance,
                postCurrency, "Purchase cost difference (stock sold) — " + bill.getSupplierInvoiceNo());

        // Input VAT (ADR-0017 D-7): DR VAT_INPUT in base — convert face VAT independently
        BigDecimal baseVat = BigDecimal.ZERO;
        if (bill.getVatAmount().compareTo(BigDecimal.ZERO) > 0) {
            baseVat = fxConverter.toBase(
                    bill.getVatAmount(), docCurrency, companyId, bill.getBillDate()).baseAmount();
            ChartOfAccount vatAcct = glConfig.resolve(companyId, GlConfigKey.VAT_INPUT);
            glLines.add(new LineDraft(vatAcct.getId(),
                    baseVat, BigDecimal.ZERO,
                    postCurrency, "Input VAT — " + bill.getSupplierInvoiceNo()));
        }

        // CR Accounts Payable — BALANCING PLUG: exact complement of all DR legs (D-3/D-8).
        // Absorbs any HALF_UP rounding residual; the unchanged GL Σ-check passes by construction.
        List<BigDecimal> drLegs = new ArrayList<>();
        drLegs.add(baseGoodsNet);
        drLegs.add(baseInventoryVariance);
        drLegs.add(baseCogsVariance);
        drLegs.add(baseServiceTotal);
        drLegs.add(baseVat);
        int plugScale = grossConv.baseAmount().scale();
        BigDecimal baseAp = fxConverter.balancingPlug(
                drLegs.stream().map(BigDecimal::negate).toList(), plugScale);

        glLines.add(new LineDraft(apAcct.getId(),
                BigDecimal.ZERO, baseAp,
                postCurrency, "AP control — " + bill.getSupplierInvoiceNo()));

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId,
                bill.getBranchId(),
                bill.getBillDate(),
                "AP Bill " + bill.getSupplierInvoiceNo(),
                JournalSourceType.AP_BILL,
                bill.getUid(),
                null,
                actorId(),
                glLines);

        JournalEntryDto posted = glPosting.post(draft);

        // The Inventory share of the cost difference must also reach the stock sub-ledger, or
        // Σ on_hand_value drifts from GL 1300. Same TX as the journal (outbox, ADR-0009 D-3); the
        // stock module re-averages the product cost and posts no GL of its own.
        if (!revaluations.isEmpty()) {
            outbox.publish(DomainEventType.BILL_COST_VARIANCE,
                    DomainEventType.AGG_SUPPLIER_BILL,
                    bill.getId(), bill.getUid(), companyId, bill.getBranchId(),
                    new BillCostVariancePayload(bill.getUid(), companyId, bill.getBranchId(),
                            bill.getBillNumber(), revaluations));
        }

        bill.setPostedGlEntryUid(posted.uid());
        bill.setMatchedAt(Instant.now());
        bill.setMatchedBy(actorId());
        bill.setOutstandingAmount(bill.getGrossAmount());

        // Stamp FX triple on the bill (D-4): base_gross_amount + fx_rate + rate_at.
        // The base gross is the AP credit actually posted (the balancing plug), so the bill's base
        // outstanding and GL AP-control agree to the minor unit.
        stampFx(bill, grossConv, baseAp);

        audit.record(AuditEvent.of(AuditActions.AP_BILL_POST, "supplier_bills",
                        bill.getId(), bill.getUid())
                .detail(Map.of("glEntryUid",  posted.uid(),
                        "grossAmount",  bill.getGrossAmount().toPlainString(),
                        "baseGrossAmount", grossConv.baseAmount().toPlainString(),
                        "fxRate",       grossConv.rate().toPlainString())));
    }

    /**
     * What a bill line's goods were booked at on receipt: the base value GRNI was credited with for
     * the quantity billed, the base quantity, and the product.
     */
    private record ReceiptValue(Long productId, BigDecimal billedBaseQty, BigDecimal baseValue) {}

    /**
     * The receipt value of the quantity on this bill line, in base currency — exactly what the
     * receipt credited to GRNI for it. Mirrors {@code GoodsReceiptServiceImpl.baseUnitCost}: the
     * receipt line total converted at the receipt date, spread over its base quantity (4 dp), then
     * {@code round4(baseQty × unitCost)} as the stock handler values it. Billing the whole line
     * therefore clears GRNI to the minor unit. An over-billed quantity is capped at what was
     * received; the excess is part of the cost difference. Null when the receipt line cannot be read.
     */
    private ReceiptValue receiptValueForBilledQty(Long companyId, SupplierBillLine l) {
        List<Map<String, Object>> rows = jdbc.queryForList(
                "SELECT grl.product_id, grl.received_qty, grl.qty_in_base, grl.line_cost_amount, "
                        + "grl.currency, gr.received_at "
                        + "FROM goods_receipt_lines grl "
                        + "JOIN goods_receipts gr ON gr.id = grl.goods_receipt_id "
                        + "WHERE grl.uid = ? AND grl.company_id = ? AND gr.status <> 'VOID'",
                l.getGrLineUid(), companyId);
        if (rows.isEmpty()) {
            return null;
        }
        Map<String, Object> r = rows.get(0);
        BigDecimal receivedQty = (BigDecimal) r.get("received_qty");
        BigDecimal qtyInBase   = (BigDecimal) r.get("qty_in_base");
        BigDecimal lineCost    = (BigDecimal) r.get("line_cost_amount");
        Object receivedAt      = r.get("received_at");
        if (receivedQty == null || receivedQty.signum() <= 0 || qtyInBase == null
                || qtyInBase.signum() <= 0 || lineCost == null || receivedAt == null
                || l.getBilledQty() == null) {
            return null;
        }
        String currency = (String) r.get("currency");
        BigDecimal baseLineCost = currency == null
                ? lineCost
                : fxConverter.toBase(lineCost, currency, companyId,
                        receiptFxDate(((java.sql.Timestamp) receivedAt).toInstant())).baseAmount();
        BigDecimal baseUnitCost = baseLineCost.divide(qtyInBase, 4, RoundingMode.HALF_UP);
        BigDecimal billedBaseQty = l.getBilledQty().compareTo(receivedQty) >= 0
                ? qtyInBase
                : l.getBilledQty().multiply(qtyInBase).divide(receivedQty, 6, RoundingMode.HALF_UP);
        BigDecimal baseValue = billedBaseQty.multiply(baseUnitCost).setScale(4, RoundingMode.HALF_UP);
        return new ReceiptValue(((Number) r.get("product_id")).longValue(), billedBaseQty, baseValue);
    }

    /**
     * The part of {@code variance} that belongs to goods still on hand: pro rata to the company's
     * on-hand quantity of the product (the moving average is company-wide, ADR-0028 D-2) against the
     * base quantity billed, capped at the whole variance. The rest was sold or used and is COGS.
     */
    private BigDecimal onHandShare(Long companyId, ReceiptValue rv, BigDecimal variance) {
        BigDecimal onHand = jdbc.queryForObject(
                "SELECT COALESCE(SUM(quantity), 0) FROM stock_on_hand "
                        + "WHERE company_id = ? AND product_id = ?",
                BigDecimal.class, companyId, rv.productId());
        if (onHand == null || onHand.signum() <= 0 || rv.billedBaseQty().signum() <= 0) {
            return BigDecimal.ZERO;
        }
        if (onHand.compareTo(rv.billedBaseQty()) >= 0) {
            return variance;
        }
        return variance.multiply(onHand)
                .divide(rv.billedBaseQty(), 4, RoundingMode.HALF_UP);
    }

    /** A signed cost-difference leg: positive debits {@code key}, negative credits it; zero adds nothing. */
    private void addVarianceLeg(List<LineDraft> glLines, Long companyId, GlConfigKey key,
                                BigDecimal amount, String currency, String memo) {
        if (amount.signum() == 0) {
            return;
        }
        Long accountId = glConfig.resolve(companyId, key).getId();
        glLines.add(amount.signum() > 0
                ? new LineDraft(accountId, amount, BigDecimal.ZERO, currency, memo)
                : new LineDraft(accountId, BigDecimal.ZERO, amount.negate(), currency, memo));
    }

    /**
     * The receipt's conversion date, derived from its received instant exactly as the receipt
     * itself derived it ({@code GoodsReceiptServiceImpl.receiptFxDate}) so both use the same rate.
     */
    private static java.time.LocalDate receiptFxDate(Instant receivedAt) {
        return receivedAt.atZone(java.time.ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Writes the bill's FX stamp (rate, rate time, base gross) and its base outstanding.
     *
     * <p><b>Why a direct, write-once UPDATE and not the entity setters alone.</b> {@code fx_rate},
     * {@code base_gross_amount} and {@code rate_at} are mapped {@code updatable = false} — they are
     * immutable once stamped (BR-CUR-05). But a supplier bill is INSERTED at entry, long before it is
     * matched, so by the time this method runs those columns can never be written through JPA again:
     * Hibernate silently left every matched USD bill at the column defaults ({@code fx_rate = 1},
     * {@code base_gross_amount NULL}), and payments, debit notes and the AP balance then valued it at
     * par while the GL had it at the real rate.
     *
     * <p>Stamping at entry instead was rejected: a draft bill's amount, date and currency can still
     * change before it is matched, and an insert-only stamp taken at entry would then be wrong for
     * good; it would also make entering a USD bill fail whenever the day's rate is not yet loaded.
     * The match/post is the business moment the rate is fixed, so the stamp is written here, once,
     * by a single company-scoped UPDATE. The JPA mapping is untouched — ordinary saves still can
     * never overwrite the stamp, which keeps the immutability the mapping was protecting.
     * {@code base_outstanding_amount} is updatable and goes through the entity as before.
     */
    private void stampFx(SupplierBill bill, ConvertedAmount grossConv, BigDecimal baseGross) {
        bill.setBaseGrossAmount(baseGross);
        bill.setBaseOutstandingAmount(baseGross);
        bill.setFxRate(grossConv.rate());
        bill.setRateAt(grossConv.rateAt());
        jdbc.update("UPDATE supplier_bills SET fx_rate = ?, base_gross_amount = ?, rate_at = ? "
                        + "WHERE id = ? AND company_id = ?",
                grossConv.rate(), baseGross,
                grossConv.rateAt() != null ? java.sql.Timestamp.from(grossConv.rateAt()) : null,
                bill.getId(), bill.getCompanyId());
    }

    // -------------------------------------------------------------------------

    /**
     * Find a GR line by its uid across all GRs in the company.
     * Uses JDBC native query to avoid cross-module entity import (D-11).
     */
    private Optional<GoodsReceiptLineDto> findGrLineByUid(Long companyId, String grLineUid) {
        if (grLineUid == null) return Optional.empty();
        try {
            // The GR line uid is globally unique; find its parent GR uid via the GR service
            String grUid = jdbc.queryForObject(
                    "SELECT gr.uid FROM goods_receipt_lines grl "
                    + "JOIN goods_receipts gr ON gr.id = grl.goods_receipt_id "
                    + "WHERE grl.uid = ? AND grl.company_id = ? "
                    + "AND gr.status <> 'VOID'",
                    String.class, grLineUid, companyId);
            if (grUid == null) return Optional.empty();
            return purchaseReader.findGrLine(grUid, grLineUid);
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private BigDecimal loadTolerancePct(Long companyId) {
        try {
            BigDecimal pct = jdbc.queryForObject(
                    "SELECT price_tolerance_pct FROM ap_settings WHERE company_id = ?",
                    BigDecimal.class, companyId);
            return pct != null ? pct : DEFAULT_TOLERANCE_PCT;
        } catch (Exception e) {
            return DEFAULT_TOLERANCE_PCT;
        }
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
