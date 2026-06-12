package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.BillMatchResultDto;
import com.erp.modules.ap.domain.dto.BillMatchResultDto.LineMatchDto;
import com.erp.modules.ap.domain.entity.BillMatch;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.domain.enums.BillMatchStatus;
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
import com.erp.platform.common.repository.Lookups;
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
 *   <li>Qty: billed_qty ≤ gr received_qty (exact by default — over-billing held).
 * </ul>
 * All lines within tolerance → bill MATCHED, posts DR Purchases / CR AP-control (D-6).
 * Any over-tolerance → bill HELD, nothing posts.
 * GL failure (missing config, closed period) rolls back the whole command (D-4).
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
                                 JdbcTemplate jdbc) {
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
    }

    @Override
    public BillMatchResultDto runMatch(String billUid) {
        SupplierBill bill = Lookups.orNotFound(bills.findByUid(billUid), "SupplierBill", billUid);
        scopeGuard.assertCanActIn(RequestContext.get(), bill.getCompanyId());

        if (bill.getStatus() != SupplierBillStatus.DRAFT
                && bill.getStatus() != SupplierBillStatus.HELD) {
            throw new IllegalStateException(
                    "Bill " + billUid + " is in status " + bill.getStatus()
                            + " — can only match DRAFT or HELD bills.");
        }

        List<SupplierBillLine> billLines = lines.findBySupplierBillIdOrderByLineNo(bill.getId());
        BigDecimal tolerancePct = loadTolerancePct(bill.getCompanyId());
        BigDecimal toleranceAbs = BigDecimal.ZERO;

        List<LineMatchDto> lineResults = new ArrayList<>();
        boolean anyHeld = false;

        for (SupplierBillLine line : billLines) {
            BillMatchStatus status;
            BigDecimal priceVar = BigDecimal.ZERO;
            BigDecimal priceVarPct = BigDecimal.ZERO;
            BigDecimal qtyVar = BigDecimal.ZERO;
            BigDecimal poUnitCost = null;
            BigDecimal grReceivedQty = null;

            if (line.getPoLineUid() != null && line.getGrLineUid() != null
                    && bill.getPurchaseOrderUid() != null) {
                // Resolve PO line
                Optional<PurchaseOrderLineDto> poLineOpt =
                        purchaseReader.findPoLine(bill.getPurchaseOrderUid(), line.getPoLineUid());
                // Resolve GR line — we need the GR uid; derive from the GR line's grLineUid
                // by searching within the PO's GR. Since we only have grLineUid scalar, use a
                // separate lookup via GR search (we search across all GRs for this company below).
                Optional<GoodsReceiptLineDto> grLineOpt =
                        findGrLineByUid(bill.getCompanyId(), line.getGrLineUid());

                if (poLineOpt.isPresent() && grLineOpt.isPresent()) {
                    poUnitCost   = poLineOpt.get().unitCostAmount();
                    grReceivedQty = grLineOpt.get().qtyInBase();

                    // Price check
                    priceVar = line.getUnitCostAmount().subtract(poUnitCost);
                    BigDecimal absPriceVar = priceVar.abs();
                    if (poUnitCost.compareTo(BigDecimal.ZERO) > 0) {
                        priceVarPct = absPriceVar
                                .divide(poUnitCost, 6, RoundingMode.HALF_UP)
                                .multiply(HUNDRED);
                    }
                    BigDecimal allowedAbs = poUnitCost
                            .multiply(tolerancePct)
                            .divide(HUNDRED, 4, RoundingMode.HALF_UP)
                            .max(toleranceAbs);
                    boolean priceOk = absPriceVar.compareTo(allowedAbs) <= 0;

                    // Qty check: billed ≤ received (qty exact by default)
                    qtyVar = line.getBilledQty().subtract(grReceivedQty);
                    boolean qtyOk = line.getBilledQty().compareTo(grReceivedQty) <= 0;

                    if (!priceOk) {
                        status = BillMatchStatus.HELD_PRICE_VARIANCE;
                        anyHeld = true;
                    } else if (!qtyOk) {
                        status = BillMatchStatus.HELD_QTY_VARIANCE;
                        anyHeld = true;
                    } else {
                        status = BillMatchStatus.MATCHED;
                    }
                } else {
                    // PO/GR not resolved — treat as MATCHED (service bill or data gap)
                    status = BillMatchStatus.MATCHED;
                }
            } else {
                // No PO/GR ref — no 3-way match required (service bill / OB)
                status = BillMatchStatus.MATCHED;
            }

            // Upsert bill_match row (one per line — uq_bill_match_line)
            BillMatch match = matches.findBySupplierBillLineId(line.getId())
                    .orElse(null);
            if (match == null) {
                match = new BillMatch(
                        bill.getCompanyId(), bill.getId(), line.getId(),
                        poUnitCost, grReceivedQty, line.getBilledQty(),
                        priceVar, priceVarPct, qtyVar,
                        status, tolerancePct, toleranceAbs, actorId());
            } else {
                match.setMatchStatus(status);
                match.setMatchedAt(Instant.now());
            }
            match = matches.save(match);

            lineResults.add(new LineMatchDto(
                    line.getId(), line.getUid(), status,
                    priceVar, priceVarPct, qtyVar,
                    poUnitCost, grReceivedQty, line.getBilledQty(),
                    match.getMatchedAt()));
        }

        // Update bill status
        SupplierBillStatus newBillStatus = anyHeld
                ? SupplierBillStatus.HELD
                : SupplierBillStatus.MATCHED;
        // Assign bill_number BEFORE the status transition (finding #15). Order matters: setStatus
        // dirties the bill as non-DRAFT, and numbers.nextBill(...) runs a code_sequence query that
        // triggers a Hibernate autoflush — flushing a MATCHED-but-unnumbered row violates
        // chk_supplier_bill_number_when_posted. Numbering while the bill is still DRAFT keeps that
        // autoflush legal. Guard is idempotent (no-op if already numbered). HELD bills stay unnumbered.
        if (!anyHeld && bill.getBillNumber() == null) {
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
            throw new IllegalStateException("Bill " + billUid + " is not HELD.");
        }

        SupplierBillLine line = lines.findBySupplierBillIdAndUid(bill.getId(), billLineUid)
                .orElseThrow(() -> new NotFoundException("BillLine not found: " + billLineUid));

        BillMatch match = matches.findBySupplierBillLineId(line.getId())
                .orElseThrow(() -> new NotFoundException(
                        "No match record for bill line: " + billLineUid));

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
            SupplierBillLine l = lines.findById(m.getSupplierBillLineId()).orElse(null);
            String lUid = l != null ? l.getUid() : null;
            return new LineMatchDto(m.getSupplierBillLineId(), lUid, m.getMatchStatus(),
                    m.getPriceVarianceAmount(), m.getPriceVariancePct(), m.getQtyVariance(),
                    m.getPoUnitCostAmount(), m.getGrReceivedQty(), m.getBilledQty(),
                    m.getMatchedAt());
        }).toList();

        return new BillMatchResultDto(bill.getUid(), bill.getStatus(), lineResults);
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

        ChartOfAccount apAcct = glConfig.resolve(bill.getCompanyId(), GlConfigKey.ACCOUNTS_PAYABLE);
        List<LineDraft> glLines = new ArrayList<>();

        // ADR-0025 D-6: only P&L-relevant legs carry the dimension tag. AP control leg untagged.
        Long ccId   = bill.getCostCentreValueId();
        Long deptId = bill.getDepartmentValueId();

        // Goods lines: DR GRNI per line (clears the GRNI accrual from goods receipt).
        // GRNI is balance-sheet; posted untagged in v1 (D-6 sub-decision).
        // Service lines: DR Purchases per line — P&L leg, carry per-line project tag (ADR-0033 D-4b).
        // Threading each line's project_id onto its own LineDraft ensures multi-project bills
        // correctly attribute cost to each project in the GL roll-up (BR-PROJ-03/05).
        BigDecimal goodsNet   = BigDecimal.ZERO;
        BigDecimal serviceNet = BigDecimal.ZERO;
        for (SupplierBillLine l : billLines) {
            BigDecimal lineNet = l.getLineNetAmount() != null ? l.getLineNetAmount() : BigDecimal.ZERO;
            if (lineNet.compareTo(BigDecimal.ZERO) <= 0) continue;

            if (l.getGrLineUid() != null) {
                // Goods line: aggregated into a single GRNI clear (balance-sheet, untagged)
                goodsNet = goodsNet.add(lineNet);
            } else {
                // Service line: one LineDraft per line with the line's own project tag
                ChartOfAccount purchasesAcct = glConfig.resolve(bill.getCompanyId(), GlConfigKey.PURCHASES);
                glLines.add(new LineDraft(purchasesAcct.getId(),
                        lineNet, BigDecimal.ZERO,
                        bill.getCurrency(), "Purchases — " + bill.getSupplierInvoiceNo(),
                        ccId, deptId, null, null,
                        l.getProjectId(), l.getProjectTaskId(), null));
                serviceNet = serviceNet.add(lineNet);
            }
        }
        if (goodsNet.compareTo(BigDecimal.ZERO) > 0) {
            ChartOfAccount grniAcct = glConfig.resolve(bill.getCompanyId(), GlConfigKey.GRNI);
            glLines.add(new LineDraft(grniAcct.getId(),
                    goodsNet, BigDecimal.ZERO,
                    bill.getCurrency(), "GRNI clear — " + bill.getSupplierInvoiceNo()));
        }

        // Input VAT (ADR-0017 D-7): debit VAT_INPUT — balance-sheet, untagged in v1
        if (bill.getVatAmount().compareTo(BigDecimal.ZERO) > 0) {
            ChartOfAccount vatAcct = glConfig.resolve(bill.getCompanyId(), GlConfigKey.VAT_INPUT);
            glLines.add(new LineDraft(vatAcct.getId(),
                    bill.getVatAmount(), BigDecimal.ZERO,
                    bill.getCurrency(), "Input VAT — " + bill.getSupplierInvoiceNo()));
        }

        // CR Accounts Payable — full gross amount; balance-sheet control leg, untagged
        glLines.add(new LineDraft(apAcct.getId(),
                BigDecimal.ZERO, bill.getGrossAmount(),
                bill.getCurrency(), "AP control — " + bill.getSupplierInvoiceNo()));

        JournalEntryDraft draft = new JournalEntryDraft(
                bill.getCompanyId(),
                bill.getBranchId(),
                bill.getBillDate(),
                "AP Bill " + bill.getSupplierInvoiceNo(),
                JournalSourceType.AP_BILL,
                bill.getUid(),
                null,
                actorId(),
                glLines);

        JournalEntryDto posted = glPosting.post(draft);

        bill.setPostedGlEntryUid(posted.uid());
        bill.setMatchedAt(Instant.now());
        bill.setMatchedBy(actorId());
        bill.setOutstandingAmount(bill.getGrossAmount());

        audit.record(AuditEvent.of(AuditActions.AP_BILL_POST, "supplier_bills",
                        bill.getId(), bill.getUid())
                .detail(Map.of("glEntryUid", posted.uid(),
                        "grossAmount", bill.getGrossAmount().toPlainString(),
                        "goodsNet",    goodsNet.toPlainString(),
                        "serviceNet",  serviceNet.toPlainString())));
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
