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
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ArCreditNoteServiceImpl(ArCreditNoteRepository creditNotes,
                                    ArInvoiceRepository invoices,
                                    CustomerRepository customers,
                                    CompanyRepository companies,
                                    ArReceiptNumberGenerator numberGen,
                                    GLPostingService glPosting,
                                    GLConfigResolver glConfig,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.creditNotes = creditNotes;
        this.invoices    = invoices;
        this.customers   = customers;
        this.companies   = companies;
        this.numberGen   = numberGen;
        this.glPosting   = glPosting;
        this.glConfig    = glConfig;
        this.scopeGuard  = scopeGuard;
        this.audit       = audit;
    }

    @Override
    public ArCreditNoteDto raise(RaiseCreditNoteRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Long customerId = customers.findByCompanyIdAndUid(companyId, req.customerUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Customer not found: " + req.customerUid()));

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElse("TZS");

        BigDecimal netAmount = req.netAmount() != null ? req.netAmount() : BigDecimal.ZERO;
        BigDecimal vatAmount = req.vatAmount() != null ? req.vatAmount() : BigDecimal.ZERO;
        BigDecimal totalAmount = netAmount.add(vatAmount);

        // Resolve the target open item (if provided)
        Long arInvoiceId = null;
        ArInvoice targetInvoice = null;
        if (req.arInvoiceUid() != null) {
            targetInvoice = invoices.findByCompanyIdAndUid(companyId, req.arInvoiceUid())
                    .orElseThrow(() -> new NotFoundException("ArInvoice not found: " + req.arInvoiceUid()));
            arInvoiceId = targetInvoice.getId();
            if (totalAmount.compareTo(targetInvoice.getOutstandingAmount()) > 0) {
                throw new IllegalStateException(
                        "Credit note amount " + totalAmount
                                + " exceeds outstanding " + targetInvoice.getOutstandingAmount()
                                + " on invoice " + req.arInvoiceUid());
            }
        }

        // Post GL entry: DR Revenue + DR VAT / CR AR (D-6)
        ChartOfAccount revenueAcct = glConfig.resolve(companyId, GlConfigKey.SALES_REVENUE);
        ChartOfAccount vatAcct     = glConfig.resolve(companyId, GlConfigKey.VAT_PAYABLE);
        ChartOfAccount arAcct      = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE);

        List<LineDraft> lines = new ArrayList<>();
        if (netAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(revenueAcct.getId(), netAmount, BigDecimal.ZERO, currency,
                    "Credit note — revenue contra"));
        }
        if (vatAmount.compareTo(BigDecimal.ZERO) > 0) {
            lines.add(new LineDraft(vatAcct.getId(), vatAmount, BigDecimal.ZERO, currency,
                    "Credit note — VAT contra"));
        }
        lines.add(new LineDraft(arAcct.getId(), BigDecimal.ZERO, totalAmount, currency,
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

        // Reduce the open item outstanding (if targeted)
        if (targetInvoice != null) {
            targetInvoice.setOutstandingAmount(
                    targetInvoice.getOutstandingAmount().subtract(totalAmount));
            ArInvoiceStatus newStatus = targetInvoice.getOutstandingAmount()
                    .compareTo(BigDecimal.ZERO) == 0
                    ? ArInvoiceStatus.PAID
                    : ArInvoiceStatus.PARTIAL;
            targetInvoice.setStatus(newStatus);
            targetInvoice.setUpdatedAt(Instant.now());
            targetInvoice.setUpdatedBy(actorId());
            invoices.save(targetInvoice);
        }

        ArCreditNote note = new ArCreditNote(
                companyId,
                RequestContext.get() != null ? RequestContext.get().branchId() : null,
                customerId,
                creditNoteNumber,
                arInvoiceId,
                req.noteDate(),
                totalAmount, netAmount, vatAmount,
                currency,
                req.reason(),
                ArCreditNoteOrigin.STANDALONE,
                actorId());
        note.setGlEntryUid(posted.uid());
        note = creditNotes.save(note);

        audit.record(AuditEvent.of(AuditActions.AR_CREDITNOTE_RAISE, "ar_credit_notes",
                        note.getId(), note.getUid())
                .detail(Map.of(
                        "creditNoteNumber", creditNoteNumber,
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
