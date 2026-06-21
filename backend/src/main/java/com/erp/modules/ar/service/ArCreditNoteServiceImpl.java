package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ApplyCreditNoteRequest;
import com.erp.modules.ar.domain.dto.ApplyCreditNoteRequest.AllocationLineRequest;
import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.ArCreditNoteDto.AllocationDto;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import com.erp.modules.ar.domain.entity.ArCreditNote;
import com.erp.modules.ar.domain.entity.ArCreditNoteAllocation;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import com.erp.modules.ar.domain.enums.ArCreditNoteStatus;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArCreditNoteAllocationRepository;
import com.erp.modules.ar.repository.ArCreditNoteRepository;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.CurrencyConversionService;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises AR credit notes and applies them to open invoices (FR-AR-14, OQ-AR-04, ADR-0040 D-6).
 *
 * <p><b>GL timing (ADR-0040 D-6 — the crux):</b>
 * <ul>
 *   <li><b>RAISE</b> — posts FULL contra ONCE: DR Revenue/VAT / CR AR-control for the note's
 *       base total at the CN's fx_rate. Sets unapplied_amount = amount, status = UNAPPLIED.
 *       No invoice relief, no realized-FX at raise.</li>
 *   <li><b>APPLY</b> — sub-ledger move only: decrements invoice outstanding_amount /
 *       base_outstanding_amount, decrements CN unapplied_amount / base_unapplied_amount, updates
 *       status. Posts NOTHING to GL except the realized-FX plug per allocation (settlement_rate
 *       vs invoice rate → REALIZED_FX_GAIN/LOSS), exactly like a receipt allocation.</li>
 * </ul>
 *
 * <p>The existing invoice-targeted raise path (req.arInvoiceUid != null) becomes raise +
 * immediate auto-apply-full so current callers/tests see the same net end-state. No regression.
 *
 * <p>Balance invariant: Σ allocated + unapplied_amount == amount, enforced under
 * SELECT FOR UPDATE. The reconciliation reads: outstanding − Σ unallocated_receipts
 * − Σ unapplied_CNs == GL 1200 at every committed state.
 */
@Service
@Transactional
public class ArCreditNoteServiceImpl implements ArCreditNoteService {

    private final ArCreditNoteRepository creditNotes;
    private final ArCreditNoteAllocationRepository cnAllocations;
    private final ArInvoiceRepository invoices;
    private final CustomerRepository customers;
    private final CompanyRepository companies;
    private final ArReceiptNumberGenerator numberGen;
    private final GLPostingService glPosting;
    private final GLConfigResolver glConfig;
    private final CurrencyConversionService fxConversion;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ArCreditNoteServiceImpl(ArCreditNoteRepository creditNotes,
                                    ArCreditNoteAllocationRepository cnAllocations,
                                    ArInvoiceRepository invoices,
                                    CustomerRepository customers,
                                    CompanyRepository companies,
                                    ArReceiptNumberGenerator numberGen,
                                    GLPostingService glPosting,
                                    GLConfigResolver glConfig,
                                    CurrencyConversionService fxConversion,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.creditNotes   = creditNotes;
        this.cnAllocations = cnAllocations;
        this.invoices      = invoices;
        this.customers     = customers;
        this.companies     = companies;
        this.numberGen     = numberGen;
        this.glPosting     = glPosting;
        this.glConfig      = glConfig;
        this.fxConversion  = fxConversion;
        this.scopeGuard    = scopeGuard;
        this.audit         = audit;
    }

    // =========================================================================
    // RAISE — posts full contra once; if invoice targeted, auto-applies immediately
    // =========================================================================

    @Override
    public ArCreditNoteDto raise(RaiseCreditNoteRequest req) {
        Company company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> new NotFoundException("Company not found: " + req.companyUid()));
        Long companyId = company.getId();
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String baseCurrency = company.getBaseCurrency();

        Long customerId = customers.findByCompanyIdAndUid(companyId, req.customerUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Customer not found: " + req.customerUid()));

        // D-6: carry the document currency from the request; default to base when blank.
        String docCurrency = (req.currency() != null && !req.currency().isBlank())
                ? req.currency()
                : baseCurrency;

        BigDecimal netAmount   = req.netAmount() != null ? req.netAmount()   : BigDecimal.ZERO;
        BigDecimal vatAmount   = req.vatAmount() != null ? req.vatAmount()   : BigDecimal.ZERO;
        BigDecimal totalAmount = netAmount.add(vatAmount);

        // Resolve target invoice if provided — validation only at raise, relief at apply below
        ArInvoice targetInvoice = null;
        if (req.arInvoiceUid() != null) {
            targetInvoice = invoices.findByCompanyIdAndUid(companyId, req.arInvoiceUid())
                    .orElseThrow(() -> new NotFoundException(
                            "ArInvoice not found: " + req.arInvoiceUid()));

            // BR-AR customer-match: credit note customer must own the target invoice (issue #1)
            assertInvoiceBelongsToCustomer(targetInvoice, customerId);

            // Currency must match (BR-CUR-06)
            if (!docCurrency.equals(targetInvoice.getCurrency().value())) {
                throw new IllegalStateException(
                        "Credit note currency " + docCurrency
                                + " does not match invoice currency " + targetInvoice.getCurrency()
                                + " on invoice " + req.arInvoiceUid());
            }
            // Face check at raise (BR-AR-04)
            if (totalAmount.compareTo(targetInvoice.getOutstandingAmount()) > 0) {
                throw new IllegalStateException(
                        "Credit note amount " + totalAmount
                                + " exceeds outstanding " + targetInvoice.getOutstandingAmount()
                                + " on invoice " + req.arInvoiceUid());
            }
        }

        // ── Resolve CN rate (D-6: same mechanics as receipt settlement rate) ──
        // Identity short-circuit when docCurrency == baseCurrency (rate == 1, no FX).
        ConvertedAmount cnConversion = fxConversion.toBase(
                BigDecimal.ONE, docCurrency, companyId, req.noteDate());
        BigDecimal cnRate = cnConversion.rate();

        // ── Convert face amounts to base at CN rate ────────────────────────────
        int baseScale = baseMinorUnits(baseCurrency);
        BigDecimal baseNet   = netAmount.multiply(cnRate).setScale(baseScale, RoundingMode.HALF_UP);
        BigDecimal baseVat   = vatAmount.multiply(cnRate).setScale(baseScale, RoundingMode.HALF_UP);
        BigDecimal baseTotal = baseNet.add(baseVat); // CR AR at raise

        // ── GL entry: raise posts FULL contra ONCE (D-6) ─────────────────────
        // DR Revenue (baseNet) / DR VAT (baseVat) / CR AR-control (baseTotal).
        // NO invoice relief, NO realized-FX at raise.
        ChartOfAccount revenueAcct = glConfig.resolve(companyId, GlConfigKey.SALES_REVENUE);
        ChartOfAccount vatAcct     = glConfig.resolve(companyId, GlConfigKey.VAT_PAYABLE);
        ChartOfAccount arAcct      = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE);

        List<LineDraft> lines = new ArrayList<>();
        if (netAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(revenueAcct.getId(), baseNet, BigDecimal.ZERO, baseCurrency,
                    "Credit note — revenue contra"));
        }
        if (vatAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(vatAcct.getId(), baseVat, BigDecimal.ZERO, baseCurrency,
                    "Credit note — VAT contra"));
        }
        // CR AR-control = baseTotal (no FX plug at raise — that moves to apply)
        lines.add(new LineDraft(arAcct.getId(), BigDecimal.ZERO, baseTotal, baseCurrency,
                "AR control — credit note"));

        String creditNoteNumber = numberGen.nextCreditNote(companyId);
        JournalEntryDraft draft = new JournalEntryDraft(
                companyId,
                RequestContext.get() != null ? RequestContext.get().branchId() : null,
                req.noteDate(),
                "Credit Note " + creditNoteNumber,
                JournalSourceType.AR_CREDIT_NOTE,
                creditNoteNumber,
                null,
                actorId(),
                lines);

        JournalEntryDto posted = glPosting.post(draft);

        // ── Persist the credit note header ───────────────────────────────────
        ArCreditNoteOrigin origin = req.origin() != null
                ? req.origin() : ArCreditNoteOrigin.STANDALONE;
        Long arInvoiceId = targetInvoice != null ? targetInvoice.getId() : null;

        ArCreditNote note = new ArCreditNote(
                companyId,
                RequestContext.get() != null ? RequestContext.get().branchId() : null,
                customerId,
                creditNoteNumber,
                arInvoiceId,
                req.noteDate(),
                totalAmount, netAmount, vatAmount,
                docCurrency,
                req.reason(),
                origin,
                actorId());
        note.setGlEntryUid(posted.uid());
        note.setFxRate(cnRate);
        note.setRateAt(cnConversion.rateAt());
        note.setBaseAmount(baseTotal);
        note.setBaseUnappliedAmount(baseTotal);
        // unappliedAmount already set to totalAmount in constructor; status defaults to UNAPPLIED
        note = creditNotes.save(note);

        audit.record(AuditEvent.of(AuditActions.AR_CREDITNOTE_RAISE, "ar_credit_notes",
                        note.getId(), note.getUid())
                .detail(Map.of(
                        "creditNoteNumber", creditNoteNumber,
                        "currency", docCurrency,
                        "amount", totalAmount.toPlainString(),
                        "glEntryUid", posted.uid())));

        // ── Auto-apply to the target invoice (raise + immediate full apply) ──
        // This preserves the existing caller contract: invoice-targeted raise still nets the
        // invoice outstanding immediately (raise-and-apply in one TX).
        List<ArCreditNoteAllocation> savedAllocs = new ArrayList<>();
        if (targetInvoice != null) {
            savedAllocs = doApplyAllocations(note, companyId,
                    List.of(new AllocationLineRequest(req.arInvoiceUid(), totalAmount)),
                    baseCurrency, baseScale);
            // Reload to get updated unapplied/status after doApplyAllocations mutated it
            note = creditNotes.findById(note.getId()).orElseThrow();
        }

        return toDto(note, savedAllocs, invoices);
    }

    // =========================================================================
    // APPLY — sub-ledger move + realized-FX plug; no AR/Revenue legs
    // =========================================================================

    @Override
    public ArCreditNoteDto apply(ApplyCreditNoteRequest req) {
        ArCreditNote noteForApply = Lookups.orNotFound(
                creditNotes.findByUid(req.creditNoteUid()), "ArCreditNote", req.creditNoteUid());
        scopeGuard.assertCanActIn(RequestContext.get(), noteForApply.getCompanyId());

        if (noteForApply.getStatus() == ArCreditNoteStatus.APPLIED) {
            throw new IllegalStateException(
                    "Credit note " + req.creditNoteUid() + " is already fully APPLIED.");
        }

        Long applyCompanyId = noteForApply.getCompanyId();
        Company company = companies.findById(applyCompanyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + applyCompanyId));
        String baseCurrency = company.getBaseCurrency();
        int baseScale = baseMinorUnits(baseCurrency);

        List<ArCreditNoteAllocation> saved = doApplyAllocations(
                noteForApply, applyCompanyId, req.allocations(), baseCurrency, baseScale);

        ArCreditNote reloaded = creditNotes.findById(noteForApply.getId()).orElseThrow();

        audit.record(AuditEvent.of(AuditActions.AR_CREDITNOTE_RAISE, "ar_credit_notes",
                        reloaded.getId(), reloaded.getUid())
                .detail(Map.of("action", "apply", "creditNoteUid", req.creditNoteUid())));

        return toDto(reloaded, saved, invoices);
    }

    // =========================================================================
    // REAPPLY — delete current allocations, restore outstanding, re-apply new set
    // =========================================================================

    @Override
    public ArCreditNoteDto reapply(ApplyCreditNoteRequest req) {
        ArCreditNote initial = Lookups.orNotFound(
                creditNotes.findByUid(req.creditNoteUid()), "ArCreditNote", req.creditNoteUid());
        scopeGuard.assertCanActIn(RequestContext.get(), initial.getCompanyId());

        Long reapplyCompanyId = initial.getCompanyId();
        Company company = companies.findById(reapplyCompanyId)
                .orElseThrow(() -> new NotFoundException("Company not found: " + reapplyCompanyId));
        String baseCurrency = company.getBaseCurrency();
        int baseScale = baseMinorUnits(baseCurrency);
        BigDecimal cnRate = initial.getFxRate() != null ? initial.getFxRate() : BigDecimal.ONE;

        // 1. Restore outstanding on currently allocated invoices
        List<ArCreditNoteAllocation> existing = cnAllocations.findByCreditNoteId(initial.getId());
        for (ArCreditNoteAllocation old : existing) {
            invoices.findById(old.getArInvoiceId()).ifPresent(inv -> {
                inv.setOutstandingAmount(inv.getOutstandingAmount().add(old.getAllocatedAmount()));
                if (old.getBaseAllocatedAmount() != null) {
                    BigDecimal invoiceRate = inv.getFxRate() != null ? inv.getFxRate() : BigDecimal.ONE;
                    BigDecimal baseRelievedRestored = old.getAllocatedAmount()
                            .multiply(invoiceRate).setScale(baseScale, RoundingMode.HALF_UP);
                    BigDecimal newBase = (inv.getBaseOutstandingAmount() != null
                            ? inv.getBaseOutstandingAmount() : BigDecimal.ZERO)
                            .add(baseRelievedRestored);
                    BigDecimal cap = inv.getBaseOriginalAmount() != null
                            ? inv.getBaseOriginalAmount() : inv.getOriginalAmount();
                    inv.setBaseOutstandingAmount(newBase.min(cap));
                }
                inv.setStatus(deriveInvoiceStatus(inv.getOutstandingAmount(), inv.getOriginalAmount()));
                inv.setUpdatedAt(Instant.now());
                inv.setUpdatedBy(actorId());
                invoices.save(inv);
            });
        }
        cnAllocations.deleteByCreditNoteId(initial.getId());
        cnAllocations.flush();

        // 2. Reset CN unapplied state to full
        initial.setUnappliedAmount(initial.getAmount());
        BigDecimal baseTotal = initial.getBaseAmount() != null
                ? initial.getBaseAmount()
                : initial.getAmount().multiply(cnRate).setScale(baseScale, RoundingMode.HALF_UP);
        initial.setBaseUnappliedAmount(baseTotal);
        initial.setStatus(ArCreditNoteStatus.UNAPPLIED);
        initial.setUpdatedAt(Instant.now());
        initial.setUpdatedBy(actorId());
        ArCreditNote resetNote = creditNotes.save(initial);

        // 3. Apply new set
        List<ArCreditNoteAllocation> saved = doApplyAllocations(
                resetNote, reapplyCompanyId, req.allocations(), baseCurrency, baseScale);
        ArCreditNote reloaded = creditNotes.findById(resetNote.getId()).orElseThrow();

        audit.record(AuditEvent.of(AuditActions.AR_CREDITNOTE_RAISE, "ar_credit_notes",
                        reloaded.getId(), reloaded.getUid())
                .detail(Map.of("action", "reapply", "creditNoteUid", req.creditNoteUid())));

        return toDto(reloaded, saved, invoices);
    }

    // =========================================================================
    // READ
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public ArCreditNoteDto getByUid(String uid) {
        ArCreditNote note = Lookups.orNotFound(creditNotes.findByUid(uid), "ArCreditNote", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), note.getCompanyId());
        List<ArCreditNoteAllocation> allocs = cnAllocations.findByCreditNoteId(note.getId());
        return toDto(note, allocs, invoices);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ArCreditNoteDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return creditNotes.findByCompanyId(companyId, pageable)
                .map(n -> toDto(n, cnAllocations.findByCreditNoteId(n.getId()), invoices));
    }

    // =========================================================================
    // Core apply logic — shared by raise (auto-apply) + apply + reapply
    // =========================================================================

    /**
     * Appends {@code ar_credit_note_allocations}, decrements invoice outstanding and CN unapplied,
     * posts realized-FX plug per allocation when settlement_rate differs from invoice rate.
     * Operates under SELECT FOR UPDATE on invoices (called only from within a TX).
     */
    private List<ArCreditNoteAllocation> doApplyAllocations(
            ArCreditNote note,
            Long companyId,
            List<AllocationLineRequest> lines,
            String baseCurrency,
            int baseScale) {

        BigDecimal cnRate = note.getFxRate() != null ? note.getFxRate() : BigDecimal.ONE;

        BigDecimal totalApplied     = BigDecimal.ZERO;
        BigDecimal sumBaseRelieved  = BigDecimal.ZERO;
        BigDecimal sumBaseSettled   = BigDecimal.ZERO;
        List<ArCreditNoteAllocation> saved = new ArrayList<>();

        for (AllocationLineRequest line : lines) {
            ArInvoice inv = invoices.findByCompanyIdAndUid(companyId, line.arInvoiceUid())
                    .orElseThrow(() -> new NotFoundException(
                            "ArInvoice not found: " + line.arInvoiceUid()));

            // BR-AR customer-match: credit note customer must own the target invoice (issue #1)
            assertInvoiceBelongsToCustomer(inv, note.getCustomerId());

            // Guard: slice must not exceed current invoice outstanding (BR-AR-04)
            if (line.allocatedAmount().compareTo(inv.getOutstandingAmount()) > 0) {
                throw new IllegalStateException(
                        "Allocation " + line.allocatedAmount()
                                + " exceeds outstanding " + inv.getOutstandingAmount()
                                + " on invoice uid=" + inv.getUid() + " (BR-AR-04).");
            }

            // Guard: slice must not exceed remaining CN unapplied
            BigDecimal remaining = note.getUnappliedAmount().subtract(totalApplied);
            if (line.allocatedAmount().compareTo(remaining) > 0) {
                throw new IllegalStateException(
                        "Allocation " + line.allocatedAmount()
                                + " exceeds CN remaining unapplied " + remaining
                                + " on note uid=" + note.getUid() + ".");
            }

            // Per-allocation base amounts (mirrors receipt allocation mechanics)
            BigDecimal invoiceRate    = inv.getFxRate() != null ? inv.getFxRate() : BigDecimal.ONE;
            BigDecimal baseRelieved   = line.allocatedAmount()
                    .multiply(invoiceRate).setScale(baseScale, RoundingMode.HALF_UP);
            BigDecimal baseSettledSlice = line.allocatedAmount()
                    .multiply(cnRate).setScale(baseScale, RoundingMode.HALF_UP);

            // Decrement invoice outstanding (face + base)
            inv.setOutstandingAmount(inv.getOutstandingAmount().subtract(line.allocatedAmount()));
            BigDecimal currentBaseOutstanding = inv.getBaseOutstandingAmount() != null
                    ? inv.getBaseOutstandingAmount() : inv.getOriginalAmount();
            inv.setBaseOutstandingAmount(
                    currentBaseOutstanding.subtract(baseRelieved).max(BigDecimal.ZERO));
            inv.setStatus(deriveInvoiceStatus(inv.getOutstandingAmount(), inv.getOriginalAmount()));
            inv.setUpdatedAt(Instant.now());
            inv.setUpdatedBy(actorId());
            invoices.save(inv);

            // Persist junction row
            ArCreditNoteAllocation alloc = new ArCreditNoteAllocation(
                    companyId, note.getId(), inv.getId(),
                    line.allocatedAmount(), actorId());
            alloc.setBaseAllocatedAmount(baseSettledSlice);
            alloc.setSettlementRate(cnRate);
            saved.add(cnAllocations.save(alloc));

            totalApplied    = totalApplied.add(line.allocatedAmount());
            sumBaseRelieved = sumBaseRelieved.add(baseRelieved);
            sumBaseSettled  = sumBaseSettled.add(baseSettledSlice);
        }

        // Guard: total applied must not exceed CN unapplied (Σ invariant)
        if (totalApplied.compareTo(note.getUnappliedAmount()) > 0) {
            throw new IllegalStateException(
                    "Total allocation " + totalApplied
                            + " exceeds CN unapplied " + note.getUnappliedAmount()
                            + " (CN uid=" + note.getUid() + ").");
        }

        // ── Post realized-FX plug per this batch (OMITTED when rates identical) ──
        // delta = Σ(face × invoice_rate) − Σ(face × cn_rate)
        //   delta > 0: rate fell since invoice → DR REALIZED_FX_LOSS   (we relieve more AR base than CN costs)
        //   delta < 0: rate rose since invoice → CR REALIZED_FX_GAIN   (we relieve less AR base)
        //   delta == 0: single-currency or same rate → no FX leg (byte-identical path)
        BigDecimal fxDelta = sumBaseRelieved.subtract(sumBaseSettled);
        if (fxDelta.compareTo(BigDecimal.ZERO) != 0) {
            ChartOfAccount arAcct = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE);
            List<LineDraft> fxLines = new ArrayList<>();

            if (fxDelta.compareTo(BigDecimal.ZERO) > 0) {
                ChartOfAccount fxLossAcct = glConfig.resolve(companyId, GlConfigKey.REALIZED_FX_LOSS);
                fxLines.add(new LineDraft(fxLossAcct.getId(), fxDelta, BigDecimal.ZERO,
                        baseCurrency, "Realized FX loss — CN apply " + note.getCreditNoteNumber()));
                fxLines.add(new LineDraft(arAcct.getId(), BigDecimal.ZERO, fxDelta,
                        baseCurrency, "AR control FX plug — CN apply " + note.getCreditNoteNumber()));
            } else {
                ChartOfAccount fxGainAcct = glConfig.resolve(companyId, GlConfigKey.REALIZED_FX_GAIN);
                fxLines.add(new LineDraft(arAcct.getId(), fxDelta.negate(), BigDecimal.ZERO,
                        baseCurrency, "AR control FX plug — CN apply " + note.getCreditNoteNumber()));
                fxLines.add(new LineDraft(fxGainAcct.getId(), BigDecimal.ZERO, fxDelta.negate(),
                        baseCurrency, "Realized FX gain — CN apply " + note.getCreditNoteNumber()));
            }

            JournalEntryDraft fxDraft = new JournalEntryDraft(
                    companyId,
                    note.getBranchId(),
                    note.getNoteDate(),
                    "CN Apply FX " + note.getCreditNoteNumber(),
                    JournalSourceType.AR_CREDIT_NOTE,
                    note.getCreditNoteNumber(),
                    null,
                    actorId(),
                    fxLines);
            glPosting.post(fxDraft);
        }

        // ── Update CN unapplied_amount + status ──────────────────────────────
        BigDecimal newUnapplied = note.getUnappliedAmount().subtract(totalApplied);
        note.setUnappliedAmount(newUnapplied);
        BigDecimal currentBaseUnapplied = note.getBaseUnappliedAmount() != null
                ? note.getBaseUnappliedAmount() : sumBaseSettled; // safe fallback
        note.setBaseUnappliedAmount(
                currentBaseUnapplied.subtract(sumBaseSettled).max(BigDecimal.ZERO));
        note.setStatus(deriveCnStatus(newUnapplied, note.getAmount()));
        note.setUpdatedAt(Instant.now());
        note.setUpdatedBy(actorId());
        creditNotes.save(note);

        return saved;
    }

    // =========================================================================
    // Status helpers
    // =========================================================================

    /**
     * BR-AR customer-match guard (issue #1): an allocation may only target an invoice that belongs
     * to the same customer as the credit note. Throws {@link ConflictException} (→ HTTP 409)
     * when the invariant is violated.
     */
    private static void assertInvoiceBelongsToCustomer(ArInvoice invoice, Long expectedCustomerId) {
        if (!invoice.getCustomerId().equals(expectedCustomerId)) {
            throw new ConflictException(
                    "Allocation invoice belongs to a different customer"
                    + " (invoice customerId=" + invoice.getCustomerId()
                    + ", credit note customerId=" + expectedCustomerId + ").");
        }
    }

    private static ArInvoiceStatus deriveInvoiceStatus(BigDecimal outstanding, BigDecimal original) {
        if (outstanding.compareTo(BigDecimal.ZERO) == 0) return ArInvoiceStatus.PAID;
        if (outstanding.compareTo(original) < 0) return ArInvoiceStatus.PARTIAL;
        return ArInvoiceStatus.OPEN;
    }

    private static ArCreditNoteStatus deriveCnStatus(BigDecimal unapplied, BigDecimal amount) {
        if (unapplied.compareTo(BigDecimal.ZERO) == 0) return ArCreditNoteStatus.APPLIED;
        if (unapplied.compareTo(amount) < 0) return ArCreditNoteStatus.PARTIAL;
        return ArCreditNoteStatus.UNAPPLIED;
    }

    /**
     * Minor-unit scale for rounding. TZS=0, USD/EUR/KES/GBP=2.
     * Defaults to 2 when the currency is unrecognised (safe fallback).
     */
    static int baseMinorUnits(String currencyCode) {
        if (currencyCode == null) return 2;
        return switch (currencyCode) {
            case "TZS", "JPY", "KRW" -> 0;
            case "BHD", "KWD", "OMR" -> 3;
            default -> 2;
        };
    }

    // =========================================================================

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    static ArCreditNoteDto toDto(ArCreditNote n, List<ArCreditNoteAllocation> allocs,
                                  ArInvoiceRepository invoiceRepo) {
        List<AllocationDto> allocDtos = allocs == null ? List.of() : allocs.stream()
                .map(a -> {
                    String invUid = invoiceRepo.findById(a.getArInvoiceId())
                            .map(i -> i.getUid()).orElse(null);
                    return new AllocationDto(a.getId(), a.getArInvoiceId(), invUid,
                            a.getAllocatedAmount());
                })
                .toList();
        return new ArCreditNoteDto(
                n.getId(), n.getUid(), n.getCompanyId(), n.getCustomerId(),
                n.getCreditNoteNumber(), n.getArInvoiceId(), n.getNoteDate(),
                n.getAmount(), n.getNetAmount(), n.getVatAmount(), n.getCurrency().value(),
                n.getReason(), n.getOrigin(), n.getGlEntryUid(),
                n.getUnappliedAmount(), n.getBaseAmount(), n.getBaseUnappliedAmount(),
                n.getFxRate(), n.getStatus(),
                allocDtos);
    }
}
