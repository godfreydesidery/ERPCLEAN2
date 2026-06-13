package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArCreditNoteDto;
import com.erp.modules.ar.domain.dto.RaiseCreditNoteRequest;
import com.erp.modules.ar.domain.entity.ArCreditNote;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArCreditNoteOrigin;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
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
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.FxDocumentConverter;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises a standalone credit note that reduces a customer receivable (FR-AR-14, OQ-AR-04).
 * Posts DR Sales Revenue + DR VAT Payable / CR AR control synchronously (ADR-0014 D-4/D-6).
 *
 * <p>ADR-0036 D-3/D-4/D-5 FX fix: the credit note carries its own document currency.
 * Net and VAT amounts are converted to base via {@link FxDocumentConverter#toBase}.
 * When applied to a foreign invoice the relieved base (at the ORIGINAL invoice rate) differs
 * from the credit-note base (at the note date rate) → the residual is posted to
 * {@code REALIZED_FX_GAIN} or {@code REALIZED_FX_LOSS} as the balancing plug leg.
 * Base-currency credit notes (identity short-circuit): delta == 0, no FX leg emitted —
 * byte-identical to pre-FX behaviour (I-5).
 */
@Service
@Transactional
public class ArCreditNoteServiceImpl implements ArCreditNoteService {

    private final ArCreditNoteRepository creditNotes;
    private final ArInvoiceRepository invoices;
    private final CustomerRepository customers;
    private final CompanyRepository companies;
    private final ArReceiptNumberGenerator numberGen;
    private final GLPostingService glPosting;
    private final GLConfigResolver glConfig;
    private final FxDocumentConverter fxConverter;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ArCreditNoteServiceImpl(ArCreditNoteRepository creditNotes,
                                    ArInvoiceRepository invoices,
                                    CustomerRepository customers,
                                    CompanyRepository companies,
                                    ArReceiptNumberGenerator numberGen,
                                    GLPostingService glPosting,
                                    GLConfigResolver glConfig,
                                    FxDocumentConverter fxConverter,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.creditNotes = creditNotes;
        this.invoices    = invoices;
        this.customers   = customers;
        this.companies   = companies;
        this.numberGen   = numberGen;
        this.glPosting   = glPosting;
        this.glConfig    = glConfig;
        this.fxConverter = fxConverter;
        this.scopeGuard  = scopeGuard;
        this.audit       = audit;
    }

    @Override
    public ArCreditNoteDto raise(RaiseCreditNoteRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String baseCurrency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElseThrow(() -> NotFoundException.of("Company", String.valueOf(companyId)));

        Long customerId = customers.findByCompanyIdAndUid(companyId, req.customerUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Customer not found: " + req.customerUid()));

        // ADR-0036 D-5: carry the document currency from the request; default to base when blank.
        String docCurrency = (req.currency() != null && !req.currency().isBlank())
                ? req.currency()
                : baseCurrency;

        BigDecimal netAmount   = req.netAmount() != null ? req.netAmount()   : BigDecimal.ZERO;
        BigDecimal vatAmount   = req.vatAmount() != null ? req.vatAmount()   : BigDecimal.ZERO;
        BigDecimal totalAmount = netAmount.add(vatAmount);

        // Resolve the target open item (if provided)
        Long arInvoiceId = null;
        ArInvoice targetInvoice = null;
        if (req.arInvoiceUid() != null) {
            targetInvoice = invoices.findByCompanyIdAndUid(companyId, req.arInvoiceUid())
                    .orElseThrow(() -> new NotFoundException(
                            "ArInvoice not found: " + req.arInvoiceUid()));
            arInvoiceId = targetInvoice.getId();

            // ADR-0036 D-5 BR-CUR-06: credit note currency must match the invoice currency.
            if (!docCurrency.equals(targetInvoice.getCurrency())) {
                throw new IllegalStateException(
                        "Credit note currency " + docCurrency
                                + " does not match invoice currency " + targetInvoice.getCurrency()
                                + " on invoice " + req.arInvoiceUid());
            }
            // Check face outstanding in the document currency (BR-AR-04)
            if (totalAmount.compareTo(targetInvoice.getOutstandingAmount()) > 0) {
                throw new IllegalStateException(
                        "Credit note amount " + totalAmount
                                + " exceeds outstanding " + targetInvoice.getOutstandingAmount()
                                + " on invoice " + req.arInvoiceUid());
            }
        }

        // ── Convert net + VAT legs to base (ADR-0036 D-3/D-4) ─────────────────
        // Identity short-circuit: when docCurrency==base, toBase returns face unchanged, rate=1.
        ConvertedAmount convertedNet = fxConverter.toBase(
                netAmount, docCurrency, companyId, req.noteDate());
        ConvertedAmount convertedVat = fxConverter.toBase(
                vatAmount, docCurrency, companyId, req.noteDate());

        BigDecimal baseNet   = convertedNet.baseAmount();
        BigDecimal baseVat   = convertedVat.baseAmount();
        BigDecimal baseTotal = baseNet.add(baseVat); // credit-note base = sum of legs

        // ── GL entry (all lines in base currency — BR-GL-06) ──────────────────
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

        // ── Realized FX delta when applied to a foreign invoice (ADR-0036 D-5) ──
        // relievedBase = face × invoice.fxRate (the carrying base of this slice at the ORIGINAL
        //   rate, immutable BR-CUR-05). For legacy null-rate rows falls back to rate=1 (D-8).
        // creditBase   = face × CN-date rate (the base cost of issuing this credit today).
        //
        // Balancing the GL entry:
        //   DR Revenue creditBase / CR AR relievedBase / FX plug
        //   ΣDR must == ΣCR:
        //   delta = relievedBase − creditBase:
        //   delta > 0 (rate fell since invoice → relievedBase > creditBase):
        //     ΣDR(creditBase) < ΣCR(relievedBase) → need more DR → DR REALIZED_FX_LOSS delta
        //     (we relieve more AR base than the CN costs today → a loss on the AR book)
        //   delta < 0 (rate rose since invoice → relievedBase < creditBase):
        //     ΣDR(creditBase) > ΣCR(relievedBase) → need more CR → CR REALIZED_FX_GAIN (-delta)
        //     (we relieve less AR base than the CN costs today → a gain on the AR book)
        //   delta == 0 (same rate, including base-currency identity) → no FX leg emitted (I-5)
        //
        // AR control CR is the BALANCING PLUG = relievedBase (D-3).
        // Entry: DR Revenue creditBase [+DR FX_LOSS delta if>0] [+CR FX_GAIN -delta if<0] / CR AR relievedBase
        // Proof of balance:
        //   delta>0: ΣDR=creditBase+delta=relievedBase, ΣCR=relievedBase ✓
        //   delta<0: ΣDR=creditBase, ΣCR=relievedBase+(-delta)=creditBase ✓

        BigDecimal arCreditBase; // the CR-AR leg
        if (targetInvoice != null) {
            // relievedBase = totalFace × invoice's original fxRate, HALF_UP to base minor units.
            // Scale is taken from the converted leg (same rounding base used by toBase).
            BigDecimal invoiceFxRate = targetInvoice.getFxRate() != null
                    ? targetInvoice.getFxRate()
                    : BigDecimal.ONE;
            int baseScale = baseNet.scale(); // base currency minor units
            BigDecimal relievedBase = totalAmount.multiply(invoiceFxRate)
                    .setScale(baseScale, java.math.RoundingMode.HALF_UP);

            BigDecimal realizedFxDelta = relievedBase.subtract(baseTotal);

            if (realizedFxDelta.compareTo(BigDecimal.ZERO) > 0) {
                // Rate fell since invoice: relievedBase > creditBase
                // Need extra DR to balance → DR REALIZED_FX_LOSS
                ChartOfAccount fxLossAcct = glConfig.resolve(companyId, GlConfigKey.REALIZED_FX_LOSS);
                lines.add(new LineDraft(fxLossAcct.getId(), realizedFxDelta, BigDecimal.ZERO,
                        baseCurrency, "Realized FX loss — credit note " + req.arInvoiceUid()));
            } else if (realizedFxDelta.compareTo(BigDecimal.ZERO) < 0) {
                // Rate rose since invoice: relievedBase < creditBase
                // Need extra CR to balance → CR REALIZED_FX_GAIN
                ChartOfAccount fxGainAcct = glConfig.resolve(companyId, GlConfigKey.REALIZED_FX_GAIN);
                lines.add(new LineDraft(fxGainAcct.getId(), BigDecimal.ZERO,
                        realizedFxDelta.negate(),
                        baseCurrency, "Realized FX gain — credit note " + req.arInvoiceUid()));
            }
            // AR control CR = relievedBase (the balancing plug) (D-3)
            arCreditBase = relievedBase;
        } else {
            // Unapplied/standalone credit: CR AR at the base total of this note (no FX delta)
            arCreditBase = baseTotal;
        }

        lines.add(new LineDraft(arAcct.getId(), BigDecimal.ZERO, arCreditBase, baseCurrency,
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

        // ── Reduce the open item outstanding (if targeted) ────────────────────
        if (targetInvoice != null) {
            // Reduce face outstanding by credit face amount (BR-AR-04)
            targetInvoice.setOutstandingAmount(
                    targetInvoice.getOutstandingAmount().subtract(totalAmount));

            // Reduce base outstanding by relievedBase (D-4: base_outstanding_amount moves with outstanding)
            if (targetInvoice.getBaseOutstandingAmount() != null) {
                BigDecimal invoiceFxRate = targetInvoice.getFxRate() != null
                        ? targetInvoice.getFxRate()
                        : BigDecimal.ONE;
                int baseScale = convertedNet.baseAmount().scale();
                BigDecimal relievedBase = totalAmount.multiply(invoiceFxRate)
                        .setScale(baseScale, java.math.RoundingMode.HALF_UP);
                BigDecimal newBaseOutstanding = targetInvoice.getBaseOutstandingAmount()
                        .subtract(relievedBase);
                // Clamp to zero to avoid negative rounding artifact
                targetInvoice.setBaseOutstandingAmount(
                        newBaseOutstanding.compareTo(BigDecimal.ZERO) < 0
                                ? BigDecimal.ZERO
                                : newBaseOutstanding);
            }

            ArInvoiceStatus newStatus = targetInvoice.getOutstandingAmount()
                    .compareTo(BigDecimal.ZERO) == 0
                    ? ArInvoiceStatus.PAID
                    : ArInvoiceStatus.PARTIAL;
            targetInvoice.setStatus(newStatus);
            targetInvoice.setUpdatedAt(Instant.now());
            targetInvoice.setUpdatedBy(actorId());
            invoices.save(targetInvoice);
        }

        // Use the origin from the request; defaults to STANDALONE for existing callers (ADR-0021 D-11).
        ArCreditNoteOrigin origin = req.origin() != null ? req.origin() : ArCreditNoteOrigin.STANDALONE;

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
        note = creditNotes.save(note);

        audit.record(AuditEvent.of(AuditActions.AR_CREDITNOTE_RAISE, "ar_credit_notes",
                        note.getId(), note.getUid())
                .detail(Map.of(
                        "creditNoteNumber", creditNoteNumber,
                        "currency", docCurrency,
                        "amount", totalAmount.toPlainString(),
                        "glEntryUid", posted.uid())));

        return toDto(note);
    }

    @Override
    @Transactional(readOnly = true)
    public ArCreditNoteDto getByUid(String uid) {
        ArCreditNote note = Lookups.orNotFound(creditNotes.findByUid(uid), "ArCreditNote", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), note.getCompanyId());
        return toDto(note);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ArCreditNoteDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return creditNotes.findByCompanyId(companyId, pageable).map(ArCreditNoteServiceImpl::toDto);
    }

    // -------------------------------------------------------------------------

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private static ArCreditNoteDto toDto(ArCreditNote n) {
        return new ArCreditNoteDto(
                n.getId(), n.getUid(), n.getCompanyId(), n.getCustomerId(),
                n.getCreditNoteNumber(), n.getArInvoiceId(), n.getNoteDate(),
                n.getAmount(), n.getNetAmount(), n.getVatAmount(), n.getCurrency(),
                n.getReason(), n.getOrigin(), n.getGlEntryUid());
    }
}
