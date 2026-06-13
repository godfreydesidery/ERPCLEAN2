package com.erp.modules.fx.service;

import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.fx.domain.dto.FxRevaluationPreviewDto;
import com.erp.modules.fx.domain.dto.FxRevaluationPreviewLineDto;
import com.erp.modules.fx.domain.dto.FxRevaluationRunDto;
import com.erp.modules.fx.domain.dto.FxRevaluationRunExecutedPayload;
import com.erp.modules.fx.domain.dto.FxRevaluationRunLineDto;
import com.erp.modules.fx.domain.dto.PostFxRevaluationRequest;
import com.erp.modules.fx.domain.entity.FxRevaluationRun;
import com.erp.modules.fx.domain.entity.FxRevaluationRunLine;
import com.erp.modules.fx.domain.enums.FxRevaluationRunStatus;
import com.erp.modules.fx.repository.FxRevaluationRunLineRepository;
import com.erp.modules.fx.repository.FxRevaluationRunRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.entity.FiscalPeriod;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.repository.FiscalPeriodRepository;
import com.erp.modules.gl.repository.JournalEntryRepository;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.FxRateService;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Period-end unrealized FX revaluation run (ADR-0036 D-6).
 *
 * <h2>Revaluation math</h2>
 * For each foreign currency, over OPEN/PARTIAL AR invoices and open AP bills:
 * <ul>
 *   <li>{@code Σ face_outstanding} — sum of outstanding_amount (foreign)</li>
 *   <li>{@code Σ carrying_base}   — sum of base_outstanding_amount (the frozen book value in base)</li>
 *   <li>{@code revalued_base}     = round(Σ face_outstanding × spot_rate, baseMinorUnits) HALF_UP</li>
 *   <li>{@code adjustment}        = revalued_base − Σ carrying_base (signed; + = gain, − = loss)</li>
 * </ul>
 *
 * <h2>GL posting</h2>
 * ONE balanced base-currency journal posted via {@link GLPostingSafeInvoker#postInNewTx}
 * (REQUIRES_NEW) under {@code JournalSourceType.FX_REVALUATION}:
 * <ul>
 *   <li>Net gain  (adjustment &gt; 0): DR AR/AP control ·  CR UNREALIZED_FX_GAIN</li>
 *   <li>Net loss  (adjustment &lt; 0): DR UNREALIZED_FX_LOSS · CR AR/AP control</li>
 * </ul>
 * The control leg is the balancing plug so Σbase == 0 exactly (D-3).
 *
 * <h2>Reversal</h2>
 * Immediately after post: {@code glPostingService.postReversal(glEntryUid, firstDayNextPeriod,
 * FX_REVALUATION, run.uid, actorId)} — the mark-to-market is provisional and reverses at next
 * period-open so the next settlement computes realized FX off the original invoice rate.
 * Idempotency: {@code journalEntries.existsByReversalOfId} (the YearEndClose precedent).
 *
 * <p>Day-1 single-currency: if all items are in base currency, the open-foreign queries return
 * empty lists → zero lines → no GL entry posted → identical behaviour to today. D-8.
 */
@Service
@Transactional
public class FxRevaluationRunServiceImpl implements FxRevaluationRunService {

    /** Minor-unit scale for base amounts. Resolved from currencies master; 2 is the safe default. */
    private static final int DEFAULT_BASE_MINOR_UNITS = 2;

    private final FxRevaluationRunRepository     runs;
    private final FxRevaluationRunLineRepository runLines;
    private final ArInvoiceRepository            arInvoices;
    private final SupplierBillRepository         supplierBills;
    private final FiscalPeriodRepository         fiscalPeriods;
    private final JournalEntryRepository         journalEntries;
    private final CompanyRepository              companies;
    private final FxRateService                  fxRateService;
    private final GLConfigResolver               configResolver;
    private final GLPostingSafeInvoker           glSafeInvoker;
    private final GLPostingService               glPostingService;
    private final OutboxPublisher                outbox;
    private final ScopeGuard                     scopeGuard;
    private final AuditService                   audit;

    public FxRevaluationRunServiceImpl(
            FxRevaluationRunRepository runs,
            FxRevaluationRunLineRepository runLines,
            ArInvoiceRepository arInvoices,
            SupplierBillRepository supplierBills,
            FiscalPeriodRepository fiscalPeriods,
            JournalEntryRepository journalEntries,
            CompanyRepository companies,
            FxRateService fxRateService,
            GLConfigResolver configResolver,
            GLPostingSafeInvoker glSafeInvoker,
            GLPostingService glPostingService,
            OutboxPublisher outbox,
            ScopeGuard scopeGuard,
            AuditService audit) {
        this.runs            = runs;
        this.runLines        = runLines;
        this.arInvoices      = arInvoices;
        this.supplierBills   = supplierBills;
        this.fiscalPeriods   = fiscalPeriods;
        this.journalEntries  = journalEntries;
        this.companies       = companies;
        this.fxRateService   = fxRateService;
        this.configResolver  = configResolver;
        this.glSafeInvoker   = glSafeInvoker;
        this.glPostingService = glPostingService;
        this.outbox          = outbox;
        this.scopeGuard      = scopeGuard;
        this.audit           = audit;
    }

    // ── preview ───────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public FxRevaluationPreviewDto preview(Long companyId, String fiscalPeriodUid,
                                            LocalDate spotRateDate) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        FiscalPeriod period = requirePeriod(fiscalPeriodUid);
        String baseCurrency = resolveBaseCurrency(companyId);
        LocalDate rateDate = spotRateDate != null ? spotRateDate : period.getEndDate();

        List<RevalLine> lines = computeRevalLines(companyId, baseCurrency, rateDate, period);

        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        List<FxRevaluationPreviewLineDto> previewLines = new ArrayList<>();

        for (RevalLine rl : lines) {
            if (rl.adjustment.compareTo(BigDecimal.ZERO) > 0) {
                totalGain = totalGain.add(rl.adjustment);
            } else {
                totalLoss = totalLoss.add(rl.adjustment.abs());
            }
            previewLines.add(new FxRevaluationPreviewLineDto(
                    rl.sourceType, rl.currency, rl.controlAccountId,
                    rl.outstandingTxn, rl.carryingBase,
                    rl.spotRate, rl.revaluedBase, rl.adjustment));
        }

        BigDecimal netAdj = totalGain.subtract(totalLoss);
        return new FxRevaluationPreviewDto(companyId, fiscalPeriodUid, rateDate,
                totalGain, totalLoss, netAdj, previewLines);
    }

    // ── post ──────────────────────────────────────────────────────────────────

    @Override
    public FxRevaluationRunDto post(PostFxRevaluationRequest req) {
        scopeGuard.assertCanActIn(RequestContext.get(), req.companyId());

        FiscalPeriod period = requirePeriod(req.fiscalPeriodUid());

        // D-6 idempotency: one run per (company, fiscal_period)
        Optional<FxRevaluationRun> existing =
                runs.findByCompanyIdAndFiscalPeriodId(req.companyId(), period.getId());
        if (existing.isPresent()) {
            FxRevaluationRun ex = existing.get();
            if (ex.getStatus() != FxRevaluationRunStatus.PREVIEWED) {
                // Already posted or reversed — return existing (no-op)
                return toDto(ex, runLines.findByFxRevaluationRunId(ex.getId()));
            }
        }

        String baseCurrency = resolveBaseCurrency(req.companyId());
        LocalDate rateDate = req.spotRateDate() != null ? req.spotRateDate() : period.getEndDate();

        List<RevalLine> revalLines = computeRevalLines(req.companyId(), baseCurrency, rateDate, period);

        // Allocate run number (FXR-0001 pattern; simple counter from code_sequence or sequential)
        String runNumber = generateRunNumber(req.companyId());

        // Persist run header first (status = PREVIEWED until GL posts)
        FxRevaluationRun run = new FxRevaluationRun(
                req.companyId(), null, runNumber,
                period.getId(), req.postingDate(), rateDate, actorId());
        run = runs.save(run);

        // Persist run lines
        BigDecimal totalGain = BigDecimal.ZERO;
        BigDecimal totalLoss = BigDecimal.ZERO;
        List<FxRevaluationRunLine> savedLines = new ArrayList<>();

        for (RevalLine rl : revalLines) {
            FxRevaluationRunLine line = new FxRevaluationRunLine(
                    run.getId(), req.companyId(),
                    rl.sourceType, rl.currency, rl.controlAccountId,
                    rl.outstandingTxn, rl.carryingBase,
                    rl.spotRate, rl.revaluedBase, rl.adjustment, actorId());
            savedLines.add(runLines.save(line));

            if (rl.adjustment.compareTo(BigDecimal.ZERO) > 0) {
                totalGain = totalGain.add(rl.adjustment);
            } else {
                totalLoss = totalLoss.add(rl.adjustment.abs());
            }
        }

        BigDecimal netAdj = totalGain.subtract(totalLoss);
        run.setTotalGainAmount(totalGain);
        run.setTotalLossAmount(totalLoss);
        run.setNetAdjustmentAmount(netAdj);

        // Post the GL journal (REQUIRES_NEW via GLPostingSafeInvoker)
        // D-6: one balanced base-currency journal for ALL currencies combined.
        // Net gain  (netAdj > 0): DR AR/AP control × gainAmt · CR UNREALIZED_FX_GAIN × gainAmt
        // Net loss  (netAdj < 0): DR UNREALIZED_FX_LOSS × lossAmt · CR AR/AP control × lossAmt
        // We post one line per revaluation line + one aggregate FX gain/loss balancing leg.
        JournalEntryDto glEntry = postRevaluationJournal(
                req.companyId(), run.getId(), run.getUid(), req.postingDate(),
                baseCurrency, revalLines, netAdj, totalGain, totalLoss);

        if (glEntry != null) {
            run.setGlEntryUid(glEntry.uid());
            run.setStatus(FxRevaluationRunStatus.POSTED);
            run.setExecutedAt(Instant.now());

            // Schedule next-period reversal immediately (D-6 mandatory).
            // Uses REQUIRES_NEW safe-invoker so a closed/missing next-period never poisons the outer TX
            // (OQ-FX-04: "record intent + post on open" — status stays POSTED when reversal deferred).
            LocalDate nextPeriodStart = period.getEndDate().plusDays(1);
            JournalEntryDto reversal = glSafeInvoker.postReversalInNewTx(
                    glEntry.uid(), nextPeriodStart,
                    JournalSourceType.FX_REVALUATION, run.getUid(), actorId());
            if (reversal != null) {
                run.setReversalGlEntryUid(reversal.uid());
                run.setStatus(FxRevaluationRunStatus.REVERSED);
            }
            // else: next period not yet open — status stays POSTED; reversalGlEntryUid null (OQ-FX-04)
        }

        run.setUpdatedAt(Instant.now());
        run.setUpdatedBy(actorId());
        run = runs.save(run);

        // Outbox event
        outbox.publish(
                DomainEventType.FX_REVALUATION_EXECUTED,
                DomainEventType.AGG_FX_REVALUATION_RUN,
                run.getId(), run.getUid(),
                req.companyId(), null,
                new FxRevaluationRunExecutedPayload(
                        run.getUid(), req.companyId(), period.getId(),
                        req.postingDate(), rateDate,
                        totalGain, totalLoss, netAdj,
                        run.getGlEntryUid(), run.getExecutedAt()));

        audit.record(AuditEvent.of(AuditActions.FX_REVALUATION_RUN, "fx_revaluation_runs",
                        run.getId(), run.getUid())
                .detail(Map.of(
                        "runNumber", runNumber,
                        "period", req.fiscalPeriodUid(),
                        "spotRateDate", rateDate.toString(),
                        "netAdjustment", netAdj.toPlainString(),
                        "glEntryUid", String.valueOf(run.getGlEntryUid()))));

        return toDto(run, savedLines);
    }

    // ── reverse ───────────────────────────────────────────────────────────────

    @Override
    public FxRevaluationRunDto reverse(String runUid, LocalDate reversalDate) {
        FxRevaluationRun run = runs.findByUid(runUid)
                .orElseThrow(() -> NotFoundException.of("FxRevaluationRun", runUid));
        scopeGuard.assertCanActIn(RequestContext.get(), run.getCompanyId());

        if (run.getStatus() == FxRevaluationRunStatus.PREVIEWED) {
            throw new IllegalStateException("Run " + runUid + " has not been posted yet.");
        }
        if (run.getStatus() == FxRevaluationRunStatus.REVERSED) {
            // Already reversed — idempotent no-op
            return toDto(run, runLines.findByFxRevaluationRunId(run.getId()));
        }
        if (run.getGlEntryUid() == null) {
            throw new IllegalStateException(
                    "Run " + runUid + " has no GL entry to reverse (GL may have failed at post time).");
        }

        // Idempotency via existsByReversalOfId
        final String glEntryUidForLookup = run.getGlEntryUid();
        var glEntry = journalEntries.findByUid(glEntryUidForLookup)
                .orElseThrow(() -> new IllegalStateException(
                        "GL entry not found for run " + runUid + ": " + glEntryUidForLookup));
        if (journalEntries.existsByReversalOfId(glEntry.getId())) {
            // Already reversed externally — update status and return
            run.setStatus(FxRevaluationRunStatus.REVERSED);
            run.setUpdatedAt(Instant.now());
            run.setUpdatedBy(actorId());
            run = runs.save(run);
            return toDto(run, runLines.findByFxRevaluationRunId(run.getId()));
        }

        JournalEntryDto reversal = glPostingService.postReversal(
                run.getGlEntryUid(), reversalDate,
                JournalSourceType.FX_REVALUATION, run.getUid(), actorId());

        run.setReversalGlEntryUid(reversal.uid());
        run.setStatus(FxRevaluationRunStatus.REVERSED);
        run.setUpdatedAt(Instant.now());
        run.setUpdatedBy(actorId());
        run = runs.save(run);

        audit.record(AuditEvent.of(AuditActions.FX_REVALUATION_RUN, "fx_revaluation_runs",
                        run.getId(), run.getUid())
                .detail(Map.of(
                        "action", "REVERSED",
                        "reversalDate", reversalDate.toString(),
                        "reversalGlEntryUid", reversal.uid())));

        return toDto(run, runLines.findByFxRevaluationRunId(run.getId()));
    }

    // ── reads ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public FxRevaluationRunDto getByUid(String uid) {
        FxRevaluationRun run = runs.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("FxRevaluationRun", uid));
        scopeGuard.assertCanActIn(RequestContext.get(), run.getCompanyId());
        return toDto(run, runLines.findByFxRevaluationRunId(run.getId()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<FxRevaluationRunDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return runs.findByCompanyId(companyId, pageable)
                .map(r -> toDto(r, runLines.findByFxRevaluationRunId(r.getId())));
    }

    // ── core computation ──────────────────────────────────────────────────────

    /**
     * Builds the list of per-(currency, sourceType) revaluation lines.
     *
     * <p>For each foreign currency present in open AR invoices (OPEN/PARTIAL) and open AP bills
     * (MATCHED/APPROVED/PARTIALLY_PAID): compute the aggregate outstanding face and carrying base,
     * look up the spot rate on {@code rateDate}, compute the revalued base, and derive the
     * signed adjustment (ADR-0036 D-6).
     */
    private List<RevalLine> computeRevalLines(Long companyId, String baseCurrency,
                                               LocalDate rateDate, FiscalPeriod period) {
        List<RevalLine> result = new ArrayList<>();

        // --- AR revaluation ---
        ChartOfAccount arAccount = resolveAccountSilently(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE);
        Long arAccountId = arAccount != null ? arAccount.getId() : null;

        List<ArInvoice> foreignAr = arInvoices.findOpenForeignForRevaluation(companyId, baseCurrency);

        // Group by currency
        Map<String, BigDecimal[]> arByCcy = new LinkedHashMap<>();
        for (ArInvoice inv : foreignAr) {
            String ccy = inv.getCurrency();
            // [0] = Σ outstanding_amount (face), [1] = Σ base_outstanding_amount (carrying base)
            arByCcy.compute(ccy, (k, v) -> {
                if (v == null) v = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
                v[0] = v[0].add(inv.getOutstandingAmount());
                BigDecimal baseOut = inv.getBaseOutstandingAmount() != null
                        ? inv.getBaseOutstandingAmount()
                        : inv.getOutstandingAmount(); // fallback: single-currency case
                v[1] = v[1].add(baseOut);
                return v;
            });
        }

        for (Map.Entry<String, BigDecimal[]> entry : arByCcy.entrySet()) {
            String ccy = entry.getKey();
            BigDecimal totalFace = entry.getValue()[0];
            BigDecimal carryingBase = entry.getValue()[1];

            BigDecimal spotRate = fxRateService.rateOn(companyId, ccy, baseCurrency, rateDate);
            BigDecimal revaluedBase = totalFace.multiply(spotRate)
                    .setScale(DEFAULT_BASE_MINOR_UNITS, RoundingMode.HALF_UP);
            BigDecimal adjustment = revaluedBase.subtract(carryingBase)
                    .setScale(DEFAULT_BASE_MINOR_UNITS, RoundingMode.HALF_UP);

            result.add(new RevalLine("AR", ccy, arAccountId,
                    totalFace, carryingBase, spotRate, revaluedBase, adjustment));
        }

        // --- AP revaluation ---
        ChartOfAccount apAccount = resolveAccountSilently(companyId, GlConfigKey.ACCOUNTS_PAYABLE);
        Long apAccountId = apAccount != null ? apAccount.getId() : null;

        List<SupplierBill> foreignAp = supplierBills.findOpenForeignForRevaluation(companyId, baseCurrency);

        Map<String, BigDecimal[]> apByCcy = new LinkedHashMap<>();
        for (SupplierBill bill : foreignAp) {
            String ccy = bill.getCurrency();
            apByCcy.compute(ccy, (k, v) -> {
                if (v == null) v = new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO};
                v[0] = v[0].add(bill.getOutstandingAmount());
                BigDecimal baseOut = bill.getBaseOutstandingAmount() != null
                        ? bill.getBaseOutstandingAmount()
                        : bill.getOutstandingAmount();
                v[1] = v[1].add(baseOut);
                return v;
            });
        }

        for (Map.Entry<String, BigDecimal[]> entry : apByCcy.entrySet()) {
            String ccy = entry.getKey();
            BigDecimal totalFace = entry.getValue()[0];
            BigDecimal carryingBase = entry.getValue()[1];

            BigDecimal spotRate = fxRateService.rateOn(companyId, ccy, baseCurrency, rateDate);
            // AP: a payable revaluation.
            // revalued_base > carrying_base => the liability increased => LOSS (we owe more in base)
            // revalued_base < carrying_base => liability decreased => GAIN
            // So AP adjustment sign is opposite to AR: gain = carrying - revalued
            // But we use the same ADR-0036 D-6 formula (revalued - carrying), and the GL legs
            // account for the AP direction by using the AP control account on the opposite side.
            BigDecimal revaluedBase = totalFace.multiply(spotRate)
                    .setScale(DEFAULT_BASE_MINOR_UNITS, RoundingMode.HALF_UP);
            // For AP: the adjustment from the company's perspective:
            // If revalued > carrying: we owe MORE in base => loss (negative)
            // If revalued < carrying: we owe LESS in base => gain (positive)
            // Sign: carrying - revalued (opposite to AR)
            BigDecimal adjustment = carryingBase.subtract(revaluedBase)
                    .setScale(DEFAULT_BASE_MINOR_UNITS, RoundingMode.HALF_UP);

            result.add(new RevalLine("AP", ccy, apAccountId,
                    totalFace, carryingBase, spotRate, revaluedBase, adjustment));
        }

        return result;
    }

    /**
     * Builds and posts a single balanced base-currency journal for the entire revaluation run
     * via {@link GLPostingSafeInvoker#postInNewTx} (REQUIRES_NEW).
     *
     * <p>For each revaluation line:
     * <ul>
     *   <li>gain (adj > 0): DR control account · CR UNREALIZED_FX_GAIN</li>
     *   <li>loss (adj < 0): DR UNREALIZED_FX_LOSS · CR control account</li>
     * </ul>
     * The entry balances by construction (Σ debits == Σ credits in base).
     * Returns null on GL infrastructure failure (missing config, closed period).
     */
    private JournalEntryDto postRevaluationJournal(Long companyId, Long runId, String runUid,
                                                     LocalDate postingDate, String baseCurrency,
                                                     List<RevalLine> revalLines,
                                                     BigDecimal netAdj,
                                                     BigDecimal totalGain, BigDecimal totalLoss) {
        if (revalLines.isEmpty() || netAdj.compareTo(BigDecimal.ZERO) == 0) {
            // No foreign exposure or zero net adjustment — no journal needed
            return null;
        }

        List<LineDraft> lines = new ArrayList<>();

        for (RevalLine rl : revalLines) {
            if (rl.adjustment.compareTo(BigDecimal.ZERO) == 0) continue;

            BigDecimal absAdj = rl.adjustment.abs();

            if (rl.adjustment.compareTo(BigDecimal.ZERO) > 0) {
                // Gain: DR control (AR or AP account) / CR UNREALIZED_FX_GAIN
                // For AR: revaluing the receivable upward => DR AR control
                // For AP: revaluing the payable downward (gain) => CR AP control (reduce liability)
                if ("AR".equals(rl.sourceType) && rl.controlAccountId != null) {
                    lines.add(new LineDraft(rl.controlAccountId, absAdj, BigDecimal.ZERO,
                            baseCurrency, "FX reval gain - AR " + rl.currency));
                } else if ("AP".equals(rl.sourceType) && rl.controlAccountId != null) {
                    // AP gain: payable reduced => CR AP control
                    lines.add(new LineDraft(rl.controlAccountId, BigDecimal.ZERO, absAdj,
                            baseCurrency, "FX reval gain - AP " + rl.currency));
                }
            } else {
                // Loss: DR UNREALIZED_FX_LOSS / CR control
                // For AR: revaluing the receivable downward => CR AR control
                // For AP: revaluing the payable upward (loss) => DR AP control
                if ("AR".equals(rl.sourceType) && rl.controlAccountId != null) {
                    lines.add(new LineDraft(rl.controlAccountId, BigDecimal.ZERO, absAdj,
                            baseCurrency, "FX reval loss - AR " + rl.currency));
                } else if ("AP".equals(rl.sourceType) && rl.controlAccountId != null) {
                    lines.add(new LineDraft(rl.controlAccountId, absAdj, BigDecimal.ZERO,
                            baseCurrency, "FX reval loss - AP " + rl.currency));
                }
            }
        }

        // Add the balancing UNREALIZED_FX_GAIN or UNREALIZED_FX_LOSS leg
        // The FX gain/loss account is resolved inside the REQUIRES_NEW TX
        // We build partial draft and add the FX account legs via configResolver inside postInNewTx.
        // BUT: GLPostingSafeInvoker.postInNewTx just posts a draft — account resolution must happen
        // before we call it (GLConfigResolver.resolve requires MANDATORY TX, which is the REQUIRES_NEW).
        // Solution: pass the draft with placeholder; no — better: resolve accounts HERE then pass full draft.
        // We call configResolver directly here since we ARE in a @Transactional method (MANDATORY satisfied).
        ChartOfAccount fxGainAcct  = resolveAccountSilently(companyId, GlConfigKey.UNREALIZED_FX_GAIN);
        ChartOfAccount fxLossAcct  = resolveAccountSilently(companyId, GlConfigKey.UNREALIZED_FX_LOSS);

        if (fxGainAcct == null || fxLossAcct == null) {
            // GL not configured for FX gain/loss — skip posting (mirrors GLPostingSafeInvoker.postSaleInNewTx behavior)
            return null;
        }

        // Add the net FX gain/loss balancing leg
        if (totalGain.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(fxGainAcct.getId(), BigDecimal.ZERO, totalGain,
                    baseCurrency, "Unrealized FX gain - run " + runUid));
        }
        if (totalLoss.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(fxLossAcct.getId(), totalLoss, BigDecimal.ZERO,
                    baseCurrency, "Unrealized FX loss - run " + runUid));
        }

        if (lines.size() < 2) {
            // Cannot post a single-line entry
            return null;
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, null, postingDate,
                "Period-end FX revaluation run " + runUid,
                JournalSourceType.FX_REVALUATION, runUid,
                null, actorId(), lines);

        return glSafeInvoker.postInNewTx(draft);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private FiscalPeriod requirePeriod(String uid) {
        return fiscalPeriods.findByUid(uid)
                .orElseThrow(() -> NotFoundException.of("FiscalPeriod", uid));
    }

    private String resolveBaseCurrency(Long companyId) {
        return companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));
    }

    /** Resolves a GL config account without throwing; returns null if not configured. */
    private ChartOfAccount resolveAccountSilently(Long companyId, GlConfigKey key) {
        try {
            return configResolver.resolve(companyId, key);
        } catch (Exception ex) {
            return null;
        }
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private String generateRunNumber(Long companyId) {
        // Simple sequential: FXR-<company>-<timestamp-millis-mod-10000>
        // A proper code_sequence is wired if available; this is a safe fallback.
        long seq = System.currentTimeMillis() % 100000L;
        return String.format("FXR-%05d", seq);
    }

    private FxRevaluationRunDto toDto(FxRevaluationRun r, List<FxRevaluationRunLine> lines) {
        return new FxRevaluationRunDto(
                r.getId(), r.getUid(), r.getCompanyId(), r.getRunNumber(),
                r.getFiscalPeriodId(), r.getPostingDate(), r.getSpotRateDate(),
                r.getStatus(),
                r.getTotalGainAmount(), r.getTotalLossAmount(), r.getNetAdjustmentAmount(),
                r.getGlEntryUid(), r.getReversalGlEntryUid(), r.getExecutedAt(),
                lines.stream().map(l -> new FxRevaluationRunLineDto(
                        l.getId(), l.getUid(), l.getSourceType(), l.getCurrency(),
                        l.getControlAccountId(),
                        l.getOutstandingTxnAmount(), l.getCarryingBaseAmount(),
                        l.getSpotRate(), l.getRevaluedBaseAmount(), l.getAdjustmentAmount()))
                        .toList());
    }

    // ── internal value-object ─────────────────────────────────────────────────

    /** Internal aggregate line used during the computation pass. */
    private record RevalLine(
            String     sourceType,
            String     currency,
            Long       controlAccountId,
            BigDecimal outstandingTxn,
            BigDecimal carryingBase,
            BigDecimal spotRate,
            BigDecimal revaluedBase,
            BigDecimal adjustment
    ) {}
}
