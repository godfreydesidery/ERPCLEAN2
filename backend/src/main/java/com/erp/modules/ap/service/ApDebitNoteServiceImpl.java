package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApDebitNoteDto;
import com.erp.modules.ap.domain.dto.RaiseDebitNoteRequest;
import com.erp.modules.ap.domain.entity.ApDebitNote;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
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
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Raises AP debit notes (ADR-0015 D-2f / D-3 / D-4 / D-6).
 *
 * <p>A debit note reduces an open payable and credits the Purchases account.
 * GL: DR Accounts Payable / CR Purchases [/ CR VAT_PAYABLE if VAT portion].
 * Optionally linked to a specific bill; if linked, reduces that bill's outstanding.
 */
@Service
@Transactional
public class ApDebitNoteServiceImpl implements ApDebitNoteService {

    private final ApDebitNoteRepository  notes;
    private final SupplierBillRepository bills;
    private final CompanyRepository      companies;
    private final SupplierRepository     suppliers;
    private final ApBillNumberGenerator  numbers;
    private final GLPostingService       glPosting;
    private final GLConfigResolver       glConfig;
    private final ScopeGuard             scopeGuard;
    private final AuditService           audit;

    public ApDebitNoteServiceImpl(ApDebitNoteRepository notes,
                                   SupplierBillRepository bills,
                                   CompanyRepository companies,
                                   SupplierRepository suppliers,
                                   ApBillNumberGenerator numbers,
                                   GLPostingService glPosting,
                                   GLConfigResolver glConfig,
                                   ScopeGuard scopeGuard,
                                   AuditService audit) {
        this.notes      = notes;
        this.bills      = bills;
        this.companies  = companies;
        this.suppliers  = suppliers;
        this.numbers    = numbers;
        this.glPosting  = glPosting;
        this.glConfig   = glConfig;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public ApDebitNoteDto raise(RaiseDebitNoteRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Long supplierId = suppliers.findByCompanyIdAndUid(companyId, req.supplierUid())
                .map(s -> s.getId())
                .orElseThrow(() -> new NotFoundException("Supplier: " + req.supplierUid()));

        BigDecimal vatAmount  = req.vatAmount() != null ? req.vatAmount() : BigDecimal.ZERO;
        BigDecimal grossAmount = req.netAmount().add(vatAmount);

        // Resolve bill if provided
        Long supplierBillId = null;
        String currency     = "TZS";
        Long branchId       = branchId();

        if (req.supplierBillUid() != null && !req.supplierBillUid().isBlank()) {
            SupplierBill bill = bills.findByCompanyIdAndUid(companyId, req.supplierBillUid())
                    .orElseThrow(() -> new NotFoundException("SupplierBill: " + req.supplierBillUid()));
            supplierBillId = bill.getId();
            currency       = bill.getCurrency();
            branchId       = bill.getBranchId();

            // Reduce outstanding (SELECT FOR UPDATE not needed here — debit note has its own GL lock)
            BigDecimal newOutstanding = bill.getOutstandingAmount().subtract(grossAmount)
                    .max(BigDecimal.ZERO);
            bill.setOutstandingAmount(newOutstanding);
            bill.setStatus(newOutstanding.compareTo(BigDecimal.ZERO) == 0
                    ? SupplierBillStatus.PAID
                    : bill.getStatus());
            bills.save(bill);
        }

        String noteNum = numbers.nextDebitNote(companyId);
        ApDebitNote note = new ApDebitNote(
                companyId, branchId, supplierId,
                noteNum, supplierBillId,
                req.noteDate(), grossAmount, req.netAmount(), vatAmount,
                currency, req.reason(), actorId());
        if (req.origin() != null) {
            note.setOrigin(req.origin());
        }
        note = notes.save(note);

        // GL: DR AP / CR Purchases [+ CR VAT_PAYABLE if VAT > 0]
        JournalEntryDto posted = postDebitNoteToGl(note, companyId, branchId, currency);
        note.setGlEntryUid(posted.uid());
        note = notes.save(note);

        audit.record(AuditEvent.of(AuditActions.AP_DEBITNOTE_RAISE, "ap_debit_notes",
                        note.getId(), note.getUid())
                .detail(Map.of("debitNoteNumber", noteNum,
                        "amount", grossAmount.toPlainString(),
                        "supplierId", String.valueOf(supplierId))));

        return toDto(note);
    }

    @Override
    @Transactional(readOnly = true)
    public ApDebitNoteDto getByUid(String uid) {
        ApDebitNote note = Lookups.orNotFound(notes.findByUid(uid), "ApDebitNote", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), note.getCompanyId());
        return toDto(note);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApDebitNoteDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return notes.findByCompanyId(companyId, pageable).map(ApDebitNoteServiceImpl::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApDebitNoteDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return notes.findByCompanyIdAndSupplierId(companyId, supplierId, pageable)
                .map(ApDebitNoteServiceImpl::toDto);
    }

    // -------------------------------------------------------------------------

    private JournalEntryDto postDebitNoteToGl(ApDebitNote note, Long companyId,
                                               Long branchId, String currency) {
        ChartOfAccount apAcct       = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_PAYABLE);
        ChartOfAccount purchasesAcct = glConfig.resolve(companyId, GlConfigKey.PURCHASES);

        List<LineDraft> glLines = new ArrayList<>();
        // DR AP (reduce liability)
        glLines.add(new LineDraft(apAcct.getId(),
                note.getAmount(), BigDecimal.ZERO,
                currency, "Debit note — " + note.getDebitNoteNumber()));

        // CR Purchases (net portion)
        glLines.add(new LineDraft(purchasesAcct.getId(),
                BigDecimal.ZERO, note.getNetAmount(),
                currency, "Debit note net — " + note.getDebitNoteNumber()));

        // CR VAT_INPUT (VAT portion, if any) — contra to the original input-VAT DR posted on bill-match
        // (ADR-0017 D-7: input VAT lives in VAT_INPUT, not VAT_PAYABLE).
        if (note.getVatAmount().compareTo(BigDecimal.ZERO) > 0) {
            ChartOfAccount vatAcct = glConfig.resolve(companyId, GlConfigKey.VAT_INPUT);
            glLines.add(new LineDraft(vatAcct.getId(),
                    BigDecimal.ZERO, note.getVatAmount(),
                    currency, "Debit note VAT — " + note.getDebitNoteNumber()));
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId,
                note.getNoteDate(),
                "AP Debit Note " + note.getDebitNoteNumber(),
                JournalSourceType.AP_DEBIT_NOTE,
                note.getUid(), null, actorId(), glLines);

        return glPosting.post(draft);
    }

    static ApDebitNoteDto toDto(ApDebitNote n) {
        return new ApDebitNoteDto(
                n.getId(), n.getUid(), n.getCompanyId(), n.getBranchId(), n.getSupplierId(),
                n.getDebitNoteNumber(), n.getSupplierBillId(), n.getNoteDate(),
                n.getAmount(), n.getNetAmount(), n.getVatAmount(),
                n.getCurrency(), n.getReason(), n.getGlEntryUid(), n.getOrigin());
    }

    private Long branchId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.branchId() : null;
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
