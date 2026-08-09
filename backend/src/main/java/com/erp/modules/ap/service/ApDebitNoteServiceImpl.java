package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApDebitNoteDto;
import com.erp.modules.ap.domain.dto.ApDebitNoteDto.AllocationDto;
import com.erp.modules.ap.domain.dto.ApplyDebitNoteRequest;
import com.erp.modules.ap.domain.dto.ApplyDebitNoteRequest.AllocationLineRequest;
import com.erp.modules.ap.domain.dto.RaiseDebitNoteRequest;
import com.erp.modules.ap.domain.entity.ApDebitNote;
import com.erp.modules.ap.domain.entity.ApDebitNoteAllocation;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.ApDebitNoteStatus;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.ApDebitNoteAllocationRepository;
import com.erp.modules.ap.repository.ApDebitNoteRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
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
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
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
 * Raises AP debit notes and applies them to open bills (ADR-0015 D-2f, ADR-0041 D3).
 *
 * <p><b>GL timing (ADR-0041 D3 — the crux; a precise mirror of
 * {@link com.erp.modules.ar.service.ArCreditNoteServiceImpl}):</b>
 * <ul>
 *   <li><b>RAISE</b> — posts FULL contra ONCE: DR AP-control / CR Purchases [+ CR VAT_INPUT] for
 *       the note's base total at the DN's fx_rate. Sets unapplied_amount = amount,
 *       status = UNAPPLIED. No bill relief, no realized-FX at raise.</li>
 *   <li><b>APPLY</b> — sub-ledger move only: decrements bill outstanding_amount /
 *       base_outstanding_amount, decrements DN unapplied_amount / base_unapplied_amount, updates
 *       status. Posts NOTHING to GL except the realized-FX plug per allocation (settlement_rate vs
 *       bill rate → REALIZED_FX_GAIN/LOSS), exactly like a payment allocation.</li>
 * </ul>
 *
 * <p>The bill-targeted raise path (req.supplierBillUid != null) becomes raise + immediate
 * auto-apply-full so current callers/tests see the same net end-state. No regression.
 *
 * <p>Balance invariant: Σ allocated + unapplied_amount == amount.
 */
@Service
@Transactional
public class ApDebitNoteServiceImpl implements ApDebitNoteService {

    private final ApDebitNoteRepository            notes;
    private final ApDebitNoteAllocationRepository  dnAllocations;
    private final SupplierBillRepository           bills;
    private final CompanyRepository                companies;
    private final SupplierRepository               suppliers;
    private final ApBillNumberGenerator            numbers;
    private final GLPostingService                 glPosting;
    private final GLConfigResolver                 glConfig;
    private final CurrencyConversionService        fxConversion;
    private final ScopeGuard                       scopeGuard;
    private final AuditService                      audit;

    public ApDebitNoteServiceImpl(ApDebitNoteRepository notes,
                                   ApDebitNoteAllocationRepository dnAllocations,
                                   SupplierBillRepository bills,
                                   CompanyRepository companies,
                                   SupplierRepository suppliers,
                                   ApBillNumberGenerator numbers,
                                   GLPostingService glPosting,
                                   GLConfigResolver glConfig,
                                   CurrencyConversionService fxConversion,
                                   ScopeGuard scopeGuard,
                                   AuditService audit) {
        this.notes         = notes;
        this.dnAllocations = dnAllocations;
        this.bills         = bills;
        this.companies     = companies;
        this.suppliers     = suppliers;
        this.numbers       = numbers;
        this.glPosting     = glPosting;
        this.glConfig      = glConfig;
        this.fxConversion  = fxConversion;
        this.scopeGuard    = scopeGuard;
        this.audit         = audit;
    }

    // =========================================================================
    // RAISE — posts full contra once; if bill targeted, auto-applies immediately
    // =========================================================================

    @Override
    public ApDebitNoteDto raise(RaiseDebitNoteRequest req) {
        Company company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        Long companyId = company.getId();
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String baseCurrency = company.getBaseCurrency();

        Long supplierId = suppliers.findByCompanyIdAndUid(companyId, req.supplierUid())
                .map(s -> s.getId())
                .orElseThrow(() -> new NotFoundException("Supplier not found."));

        BigDecimal netAmount   = req.netAmount() != null ? req.netAmount() : BigDecimal.ZERO;
        BigDecimal vatAmount   = req.vatAmount() != null ? req.vatAmount() : BigDecimal.ZERO;
        BigDecimal grossAmount = netAmount.add(vatAmount);

        // Resolve target bill if provided — validation only at raise; relief at apply below.
        SupplierBill targetBill = null;
        String docCurrency = baseCurrency;
        Long branchId = branchId();
        if (req.supplierBillUid() != null && !req.supplierBillUid().isBlank()) {
            targetBill = bills.findByCompanyIdAndUid(companyId, req.supplierBillUid())
                    .orElseThrow(() -> new NotFoundException("Supplier bill not found."));
            docCurrency = targetBill.getCurrency().value();
            branchId    = targetBill.getBranchId();

            // Face check at raise — DN must not exceed the bill outstanding.
            if (grossAmount.compareTo(targetBill.getOutstandingAmount()) > 0) {
                throw new IllegalStateException(
                        "The debit note amount exceeds the outstanding balance on the selected supplier bill."
                        + " Please enter an amount that does not exceed what is still owed on the bill.");
            }
        }

        // ── Resolve DN rate (same mechanics as payment settlement rate) ──
        // Identity short-circuit when docCurrency == baseCurrency (rate == 1, no FX).
        ConvertedAmount dnConversion = fxConversion.toBase(
                BigDecimal.ONE, docCurrency, companyId, req.noteDate());
        BigDecimal dnRate = dnConversion.rate();

        // ── Convert face amounts to base at DN rate ──
        int baseScale = baseMinorUnits(baseCurrency);
        BigDecimal baseNet   = netAmount.multiply(dnRate).setScale(baseScale, RoundingMode.HALF_UP);
        BigDecimal baseVat   = vatAmount.multiply(dnRate).setScale(baseScale, RoundingMode.HALF_UP);
        BigDecimal baseTotal = baseNet.add(baseVat); // DR AP at raise

        // ── Persist the debit note header first (need id + uid for the journal source ref) ──
        String noteNum = numbers.nextDebitNote(companyId);
        Long supplierBillId = targetBill != null ? targetBill.getId() : null;
        ApDebitNote note = new ApDebitNote(
                companyId, branchId, supplierId,
                noteNum, supplierBillId,
                req.noteDate(), grossAmount, netAmount, vatAmount,
                docCurrency, req.reason(), actorId());
        // origin is NOT NULL (free-text source tag). Default standalone-raised DNs to STANDALONE;
        // return-sourced callers pass req.origin() = "PURCHASE_RETURN:{uid}".
        note.setOrigin(req.origin() != null
                ? req.origin()
                : com.erp.modules.ap.domain.enums.ApDebitNoteOrigin.STANDALONE.name());
        note.setFxRate(dnRate);
        note.setRateAt(dnConversion.rateAt());
        note.setBaseAmount(baseTotal);
        note.setBaseUnappliedAmount(baseTotal);
        // unappliedAmount already set to grossAmount in constructor; status defaults to UNAPPLIED
        note = notes.save(note);

        // ── GL: raise posts FULL contra ONCE (D3) ──
        // DR AP-control (baseTotal) / CR Purchases (baseNet) [+ CR VAT_INPUT (baseVat)].
        // NO bill relief, NO realized-FX at raise.
        JournalEntryDto posted = postRaiseContraToGl(note, companyId, branchId, baseCurrency,
                baseNet, baseVat, baseTotal);
        note.setGlEntryUid(posted.uid());
        note = notes.save(note);

        audit.record(AuditEvent.of(AuditActions.AP_DEBITNOTE_RAISE, "ap_debit_notes",
                        note.getId(), note.getUid())
                .detail(Map.of("debitNoteNumber", noteNum,
                        "amount", grossAmount.toPlainString(),
                        "currency", docCurrency,
                        "glEntryUid", posted.uid())));

        // ── Auto-apply to the target bill (raise + immediate full apply) ──
        // Preserves the existing caller contract: bill-targeted raise still nets the bill
        // outstanding immediately (raise-and-apply in one TX).
        List<ApDebitNoteAllocation> savedAllocs = new ArrayList<>();
        if (targetBill != null) {
            savedAllocs = doApplyAllocations(note, companyId,
                    List.of(new AllocationLineRequest(req.supplierBillUid(), grossAmount)),
                    baseCurrency, baseScale);
            note = notes.findById(note.getId()).orElseThrow();
        }

        return toDto(note, savedAllocs, bills);
    }

    // =========================================================================
    // APPLY — sub-ledger move + realized-FX plug; no AP/Purchases legs
    // =========================================================================

    @Override
    public ApDebitNoteDto apply(ApplyDebitNoteRequest req) {
        ApDebitNote noteForApply = Lookups.orNotFound(
                notes.findByUid(req.debitNoteUid()), "ApDebitNote", req.debitNoteUid());
        scopeGuard.assertCanActIn(RequestContext.get(), noteForApply.getCompanyId());

        if (noteForApply.getStatus() == ApDebitNoteStatus.APPLIED) {
            // DN uid not exposed in the message
            throw new IllegalStateException(
                    "This debit note has already been fully applied and cannot be applied again.");
        }

        Long applyCompanyId = noteForApply.getCompanyId();
        Company company = companies.findById(applyCompanyId)
                .orElseThrow(() -> new NotFoundException("Company not found."));
        String baseCurrency = company.getBaseCurrency();
        int baseScale = baseMinorUnits(baseCurrency);

        List<ApDebitNoteAllocation> saved = doApplyAllocations(
                noteForApply, applyCompanyId, req.allocations(), baseCurrency, baseScale);

        ApDebitNote reloaded = notes.findById(noteForApply.getId()).orElseThrow();

        audit.record(AuditEvent.of(AuditActions.AP_DEBITNOTE_RAISE, "ap_debit_notes",
                        reloaded.getId(), reloaded.getUid())
                .detail(Map.of("action", "apply", "debitNoteUid", req.debitNoteUid())));

        return toDto(reloaded, saved, bills);
    }

    // =========================================================================
    // REAPPLY — delete current allocations, restore outstanding, re-apply new set
    // =========================================================================

    @Override
    public ApDebitNoteDto reapply(ApplyDebitNoteRequest req) {
        ApDebitNote initial = Lookups.orNotFound(
                notes.findByUid(req.debitNoteUid()), "ApDebitNote", req.debitNoteUid());
        scopeGuard.assertCanActIn(RequestContext.get(), initial.getCompanyId());

        Long reapplyCompanyId = initial.getCompanyId();
        Company company = companies.findById(reapplyCompanyId)
                .orElseThrow(() -> new NotFoundException("Company not found."));
        String baseCurrency = company.getBaseCurrency();
        int baseScale = baseMinorUnits(baseCurrency);

        // 1. Restore outstanding on currently allocated bills
        List<ApDebitNoteAllocation> existing = dnAllocations.findByDebitNoteId(initial.getId());
        for (ApDebitNoteAllocation old : existing) {
            bills.findById(old.getSupplierBillId()).ifPresent(bill -> {
                bill.setOutstandingAmount(bill.getOutstandingAmount().add(old.getAllocatedAmount()));
                if (old.getBaseAllocatedAmount() != null) {
                    BigDecimal billRate = bill.getFxRate() != null ? bill.getFxRate() : BigDecimal.ONE;
                    BigDecimal baseRelievedRestored = old.getAllocatedAmount()
                            .multiply(billRate).setScale(baseScale, RoundingMode.HALF_UP);
                    BigDecimal newBase = (bill.getBaseOutstandingAmount() != null
                            ? bill.getBaseOutstandingAmount() : BigDecimal.ZERO)
                            .add(baseRelievedRestored);
                    BigDecimal cap = bill.getBaseGrossAmount() != null
                            ? bill.getBaseGrossAmount() : bill.getGrossAmount();
                    bill.setBaseOutstandingAmount(newBase.min(cap));
                }
                bill.setStatus(deriveBillStatus(bill.getOutstandingAmount(), bill.getGrossAmount()));
                bill.setUpdatedAt(Instant.now());
                bill.setUpdatedBy(actorId());
                bills.save(bill);
            });
        }
        dnAllocations.deleteByDebitNoteId(initial.getId());
        dnAllocations.flush();

        // 2. Reset DN unapplied state to full
        BigDecimal dnRate = initial.getFxRate() != null ? initial.getFxRate() : BigDecimal.ONE;
        initial.setUnappliedAmount(initial.getAmount());
        BigDecimal baseTotal = initial.getBaseAmount() != null
                ? initial.getBaseAmount()
                : initial.getAmount().multiply(dnRate).setScale(baseScale, RoundingMode.HALF_UP);
        initial.setBaseUnappliedAmount(baseTotal);
        initial.setStatus(ApDebitNoteStatus.UNAPPLIED);
        initial.setUpdatedAt(Instant.now());
        initial.setUpdatedBy(actorId());
        ApDebitNote resetNote = notes.save(initial);

        // 3. Apply new set
        List<ApDebitNoteAllocation> saved = doApplyAllocations(
                resetNote, reapplyCompanyId, req.allocations(), baseCurrency, baseScale);
        ApDebitNote reloaded = notes.findById(resetNote.getId()).orElseThrow();

        audit.record(AuditEvent.of(AuditActions.AP_DEBITNOTE_RAISE, "ap_debit_notes",
                        reloaded.getId(), reloaded.getUid())
                .detail(Map.of("action", "reapply", "debitNoteUid", req.debitNoteUid())));

        return toDto(reloaded, saved, bills);
    }

    // =========================================================================
    // READ
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public ApDebitNoteDto getByUid(String uid) {
        ApDebitNote note = Lookups.orNotFound(notes.findByUid(uid), "ApDebitNote", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), note.getCompanyId());
        return toDto(note, dnAllocations.findByDebitNoteId(note.getId()), bills);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApDebitNoteDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return notes.findByCompanyId(companyId, pageable)
                .map(n -> toDto(n, dnAllocations.findByDebitNoteId(n.getId()), bills));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApDebitNoteDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return notes.findByCompanyIdAndSupplierId(companyId, supplierId, pageable)
                .map(n -> toDto(n, dnAllocations.findByDebitNoteId(n.getId()), bills));
    }

    // =========================================================================
    // GL — raise full contra
    // =========================================================================

    /**
     * Raise journal (base currency): DR AP-control (baseTotal) / CR Purchases (baseNet)
     * [+ CR VAT_INPUT (baseVat)]. Balances by construction: DR == baseTotal == baseNet + baseVat == ΣCR.
     */
    private JournalEntryDto postRaiseContraToGl(ApDebitNote note, Long companyId, Long branchId,
                                                 String baseCurrency, BigDecimal baseNet,
                                                 BigDecimal baseVat, BigDecimal baseTotal) {
        ChartOfAccount apAcct        = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_PAYABLE);
        ChartOfAccount purchasesAcct = glConfig.resolve(companyId, GlConfigKey.PURCHASES);

        List<LineDraft> lines = new ArrayList<>();
        // DR AP-control = baseTotal (no FX plug at raise — that moves to apply)
        lines.add(new LineDraft(apAcct.getId(), baseTotal, BigDecimal.ZERO, baseCurrency,
                "AP control — debit note " + note.getDebitNoteNumber()));
        // CR Purchases (net portion)
        if (baseNet.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(purchasesAcct.getId(), BigDecimal.ZERO, baseNet, baseCurrency,
                    "Debit note net — " + note.getDebitNoteNumber()));
        }
        // CR VAT_INPUT (contra to input-VAT DR posted on bill-match; ADR-0017 D-7)
        if (baseVat.compareTo(BigDecimal.ZERO) > 0) {
            ChartOfAccount vatAcct = glConfig.resolve(companyId, GlConfigKey.VAT_INPUT);
            lines.add(new LineDraft(vatAcct.getId(), BigDecimal.ZERO, baseVat, baseCurrency,
                    "Debit note VAT — " + note.getDebitNoteNumber()));
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId,
                note.getNoteDate(),
                "AP Debit Note " + note.getDebitNoteNumber(),
                JournalSourceType.AP_DEBIT_NOTE,
                note.getUid(), null, actorId(), lines);

        return glPosting.post(draft);
    }

    // =========================================================================
    // Core apply logic — shared by raise (auto-apply) + apply + reapply
    // =========================================================================

    /**
     * Appends {@code ap_debit_note_allocations}, decrements bill outstanding and DN unapplied,
     * posts realized-FX plug per allocation when settlement_rate differs from bill rate.
     * Mirrors {@link com.erp.modules.ar.service.ArCreditNoteServiceImpl#doApplyAllocations} with
     * signs inverted for the AP side (DR AP / CR bill-base).
     */
    private List<ApDebitNoteAllocation> doApplyAllocations(
            ApDebitNote note,
            Long companyId,
            List<AllocationLineRequest> lines,
            String baseCurrency,
            int baseScale) {

        BigDecimal dnRate = note.getFxRate() != null ? note.getFxRate() : BigDecimal.ONE;

        BigDecimal totalApplied    = BigDecimal.ZERO;
        BigDecimal sumBaseRelieved = BigDecimal.ZERO; // Σ(face × bill_rate)  — original AP base
        BigDecimal sumBaseSettled  = BigDecimal.ZERO; // Σ(face × dn_rate)    — DN base
        List<ApDebitNoteAllocation> saved = new ArrayList<>();

        for (AllocationLineRequest line : lines) {
            SupplierBill bill = bills.findByCompanyIdAndUid(companyId, line.supplierBillUid())
                    .orElseThrow(() -> new NotFoundException("Supplier bill not found."));

            // Guard: slice must not exceed current bill outstanding (bill uid not exposed)
            if (line.allocatedAmount().compareTo(bill.getOutstandingAmount()) > 0) {
                throw new IllegalStateException(
                        "The amount being applied exceeds the outstanding balance on one of the selected bills."
                        + " Please reduce the allocation amount for that bill.");
            }

            // Guard: slice must not exceed remaining DN unapplied (DN uid not exposed)
            BigDecimal remaining = note.getUnappliedAmount().subtract(totalApplied);
            if (line.allocatedAmount().compareTo(remaining) > 0) {
                throw new IllegalStateException(
                        "The amount being applied exceeds the remaining unapplied balance on this debit note."
                        + " Please reduce the allocation amount so it does not exceed the available credit.");
            }

            // Per-allocation base amounts (mirrors payment allocation mechanics)
            BigDecimal billRate       = bill.getFxRate() != null ? bill.getFxRate() : BigDecimal.ONE;
            BigDecimal baseRelieved   = line.allocatedAmount()
                    .multiply(billRate).setScale(baseScale, RoundingMode.HALF_UP);
            BigDecimal baseSettledSlice = line.allocatedAmount()
                    .multiply(dnRate).setScale(baseScale, RoundingMode.HALF_UP);

            // Decrement bill outstanding (face + base)
            bill.setOutstandingAmount(bill.getOutstandingAmount().subtract(line.allocatedAmount()));
            BigDecimal currentBaseOutstanding = bill.getBaseOutstandingAmount() != null
                    ? bill.getBaseOutstandingAmount() : bill.getGrossAmount();
            bill.setBaseOutstandingAmount(
                    currentBaseOutstanding.subtract(baseRelieved).max(BigDecimal.ZERO));
            bill.setStatus(deriveBillStatus(bill.getOutstandingAmount(), bill.getGrossAmount()));
            bill.setUpdatedAt(Instant.now());
            bill.setUpdatedBy(actorId());
            bills.save(bill);

            // Persist junction row
            ApDebitNoteAllocation alloc = new ApDebitNoteAllocation(
                    companyId, note.getId(), bill.getId(),
                    line.allocatedAmount(), actorId());
            alloc.setBaseAllocatedAmount(baseSettledSlice);
            alloc.setSettlementRate(dnRate);
            saved.add(dnAllocations.save(alloc));

            totalApplied    = totalApplied.add(line.allocatedAmount());
            sumBaseRelieved = sumBaseRelieved.add(baseRelieved);
            sumBaseSettled  = sumBaseSettled.add(baseSettledSlice);
        }

        // Guard: total applied must not exceed DN unapplied (Σ invariant — DN uid not exposed)
        if (totalApplied.compareTo(note.getUnappliedAmount()) > 0) {
            throw new IllegalStateException(
                    "The combined allocation total exceeds the unapplied balance remaining on this debit note."
                    + " Please adjust the allocation lines so they do not exceed the available credit.");
        }

        // ── Post realized-FX plug per this batch (OMITTED when rates identical) ──
        // delta = Σ(face × bill_rate) − Σ(face × dn_rate) = sumBaseRelieved − sumBaseSettled
        //   delta > 0: bill base relieved exceeds DN base → CR REALIZED_FX_GAIN (we owe less)
        //   delta < 0: DN base exceeds bill base relieved → DR REALIZED_FX_LOSS
        //   delta == 0: single-currency or same rate → no FX leg (byte-identical path)
        BigDecimal fxDelta = sumBaseRelieved.subtract(sumBaseSettled);
        if (fxDelta.compareTo(BigDecimal.ZERO) != 0) {
            ChartOfAccount apAcct = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_PAYABLE);
            List<LineDraft> fxLines = new ArrayList<>();

            if (fxDelta.compareTo(BigDecimal.ZERO) > 0) {
                // We relieved MORE AP base than the DN cost → realized FX GAIN.
                // DR AP plug / CR Realized FX Gain.
                ChartOfAccount fxGainAcct = glConfig.resolve(companyId, GlConfigKey.REALIZED_FX_GAIN);
                fxLines.add(new LineDraft(apAcct.getId(), fxDelta, BigDecimal.ZERO,
                        baseCurrency, "AP control FX plug — DN apply " + note.getDebitNoteNumber()));
                fxLines.add(new LineDraft(fxGainAcct.getId(), BigDecimal.ZERO, fxDelta,
                        baseCurrency, "Realized FX gain — DN apply " + note.getDebitNoteNumber()));
            } else {
                // We relieved LESS AP base than the DN cost → realized FX LOSS.
                // DR Realized FX Loss / CR AP plug.
                ChartOfAccount fxLossAcct = glConfig.resolve(companyId, GlConfigKey.REALIZED_FX_LOSS);
                fxLines.add(new LineDraft(fxLossAcct.getId(), fxDelta.negate(), BigDecimal.ZERO,
                        baseCurrency, "Realized FX loss — DN apply " + note.getDebitNoteNumber()));
                fxLines.add(new LineDraft(apAcct.getId(), BigDecimal.ZERO, fxDelta.negate(),
                        baseCurrency, "AP control FX plug — DN apply " + note.getDebitNoteNumber()));
            }

            JournalEntryDraft fxDraft = new JournalEntryDraft(
                    companyId,
                    note.getBranchId(),
                    note.getNoteDate(),
                    "DN Apply FX " + note.getDebitNoteNumber(),
                    JournalSourceType.AP_DEBIT_NOTE,
                    note.getUid(),
                    null,
                    actorId(),
                    fxLines);
            glPosting.post(fxDraft);
        }

        // ── Update DN unapplied_amount + status ──
        BigDecimal newUnapplied = note.getUnappliedAmount().subtract(totalApplied);
        note.setUnappliedAmount(newUnapplied);
        BigDecimal currentBaseUnapplied = note.getBaseUnappliedAmount() != null
                ? note.getBaseUnappliedAmount() : sumBaseSettled; // safe fallback
        note.setBaseUnappliedAmount(
                currentBaseUnapplied.subtract(sumBaseSettled).max(BigDecimal.ZERO));
        note.setStatus(deriveDnStatus(newUnapplied, note.getAmount()));
        note.setUpdatedAt(Instant.now());
        note.setUpdatedBy(actorId());
        notes.save(note);

        return saved;
    }

    // =========================================================================
    // Status helpers
    // =========================================================================

    /**
     * Status after a debit-note relief (mirrors {@code ApPaymentServiceImpl.billStatusAfterPayment}):
     * PAID when fully relieved, else PARTIALLY_PAID. The codebase convention is that any relief
     * touch yields PAID/PARTIALLY_PAID (a bill that has had a DN/payment applied is never restored
     * to MATCHED/APPROVED). {@code gross} is unused but kept for signature parity / future use.
     */
    private static SupplierBillStatus deriveBillStatus(BigDecimal outstanding, BigDecimal gross) {
        return outstanding.compareTo(BigDecimal.ZERO) == 0
                ? SupplierBillStatus.PAID
                : SupplierBillStatus.PARTIALLY_PAID;
    }

    private static ApDebitNoteStatus deriveDnStatus(BigDecimal unapplied, BigDecimal amount) {
        if (unapplied.compareTo(BigDecimal.ZERO) == 0) return ApDebitNoteStatus.APPLIED;
        if (unapplied.compareTo(amount) < 0) return ApDebitNoteStatus.PARTIAL;
        return ApDebitNoteStatus.UNAPPLIED;
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

    private Long branchId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.branchId() : null;
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    static ApDebitNoteDto toDto(ApDebitNote n, List<ApDebitNoteAllocation> allocs,
                                 SupplierBillRepository billRepo) {
        List<AllocationDto> allocDtos = allocs == null ? List.of() : allocs.stream()
                .map(a -> {
                    // Company-scoped, from the LOADED debit note — a note and its allocated bills
                    // always share a company, so this resolves the same row for every legitimate
                    // call while closing the confused-deputy hole the bare finder left open.
                    String billUid = billRepo.findByCompanyIdAndId(n.getCompanyId(), a.getSupplierBillId())
                            .map(b -> b.getUid()).orElse(null);
                    return new AllocationDto(a.getId(), a.getSupplierBillId(), billUid,
                            a.getAllocatedAmount());
                })
                .toList();
        return new ApDebitNoteDto(
                n.getId(), n.getUid(), n.getCompanyId(), n.getBranchId(), n.getSupplierId(),
                n.getDebitNoteNumber(), n.getSupplierBillId(), n.getNoteDate(),
                n.getAmount(), n.getNetAmount(), n.getVatAmount(),
                n.getCurrency().value(), n.getReason(), n.getGlEntryUid(), n.getOrigin(),
                n.getOriginRef(),
                n.getUnappliedAmount(), n.getBaseAmount(), n.getBaseUnappliedAmount(),
                n.getFxRate(), n.getStatus(),
                allocDtos);
    }
}
