package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArWriteOffDto;
import com.erp.modules.ar.domain.dto.WriteOffRequest;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.entity.ArWriteOff;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArWriteOffRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Writes off an uncollectable open item (FR-AR-13, BR-AR-06).
 * Posts DR Bad-Debt Expense / CR AR control synchronously (ADR-0014 D-4/D-6).
 */
@Service
@Transactional
public class ArWriteOffServiceImpl implements ArWriteOffService {

    private final ArWriteOffRepository writeOffs;
    private final ArInvoiceRepository invoices;
    private final CompanyRepository companies;
    private final GLPostingService glPosting;
    private final GLConfigResolver glConfig;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ArWriteOffServiceImpl(ArWriteOffRepository writeOffs,
                                  ArInvoiceRepository invoices,
                                  CompanyRepository companies,
                                  GLPostingService glPosting,
                                  GLConfigResolver glConfig,
                                  ScopeGuard scopeGuard,
                                  AuditService audit) {
        this.writeOffs = writeOffs;
        this.invoices  = invoices;
        this.companies = companies;
        this.glPosting = glPosting;
        this.glConfig  = glConfig;
        this.scopeGuard = scopeGuard;
        this.audit     = audit;
    }

    @Override
    public ArWriteOffDto writeOff(WriteOffRequest req) {
        ArInvoice inv = Lookups.orNotFound(invoices.findByUid(req.arInvoiceUid()),
                "ArInvoice", req.arInvoiceUid());
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());

        if (inv.getStatus() == ArInvoiceStatus.PAID
                || inv.getStatus() == ArInvoiceStatus.WRITTEN_OFF) {
            // inv.getUid() intentionally not surfaced in the user message (error-hygiene rule)
            throw new IllegalStateException(
                    "This invoice is already " + inv.getStatus() + " and cannot be written off.");
        }

        BigDecimal faceOutstanding = inv.getOutstandingAmount();
        if (faceOutstanding.compareTo(BigDecimal.ZERO) <= 0) {
            // inv.getUid() intentionally not surfaced in the user message (error-hygiene rule)
            throw new IllegalStateException(
                    "This invoice has no outstanding balance to write off.");
        }

        // ADR-0036 D-3/D-4: relieve AR and debit Bad Debt at the CARRYING BASE value.
        // baseOutstandingAmount is populated by Task A (AR open-item FX stamp).
        // For legacy rate=1 rows it is null → fall back to face (base==face when rate=1, D-8).
        BigDecimal baseRelieved = inv.getBaseOutstandingAmount() != null
                ? inv.getBaseOutstandingAmount()
                : faceOutstanding;

        // Base currency — GL engine (BR-GL-06) requires LineDraft.currency == company base (D-9).
        String baseCurrency = companies.findById(inv.getCompanyId())
                .map(c -> c.getBaseCurrency())
                .orElseThrow(() -> NotFoundException.of("Company",
                        String.valueOf(inv.getCompanyId())));

        // Post DR Bad Debt Expense / CR AR control at CARRYING BASE (D-3, D-4, D-6)
        ChartOfAccount badDebtAcct = glConfig.resolve(inv.getCompanyId(), GlConfigKey.BAD_DEBT_EXPENSE);
        ChartOfAccount arAcct      = glConfig.resolve(inv.getCompanyId(), GlConfigKey.ACCOUNTS_RECEIVABLE);

        JournalEntryDraft draft = new JournalEntryDraft(
                inv.getCompanyId(),
                inv.getBranchId(),
                req.writeOffDate(),
                "Write-off: " + inv.getDocumentNo() + " / " + inv.getUid(),
                JournalSourceType.AR_WRITEOFF,
                inv.getUid(),
                null,
                actorId(),
                List.of(
                        new LineDraft(badDebtAcct.getId(), baseRelieved, BigDecimal.ZERO,
                                baseCurrency, "Bad debt write-off — " + inv.getUid()),
                        new LineDraft(arAcct.getId(), BigDecimal.ZERO, baseRelieved,
                                baseCurrency, "AR control — write-off " + inv.getUid())
                ));

        JournalEntryDto posted = glPosting.post(draft);

        // Update the open item — zero both face and base outstanding (D-4)
        inv.setOutstandingAmount(BigDecimal.ZERO);
        if (inv.getBaseOutstandingAmount() != null) {
            inv.setBaseOutstandingAmount(BigDecimal.ZERO);
        }
        inv.setStatus(ArInvoiceStatus.WRITTEN_OFF);
        inv.setUpdatedAt(Instant.now());
        inv.setUpdatedBy(actorId());
        invoices.save(inv);

        // Create the write-off record (amount in face currency; the document face for the record)
        ArWriteOff writeOff = new ArWriteOff(
                inv.getCompanyId(), inv.getBranchId(), inv.getCustomerId(), inv.getId(),
                req.writeOffDate(), faceOutstanding, inv.getCurrency().value(), req.reason(), actorId());
        writeOff.setGlEntryUid(posted.uid());
        writeOff = writeOffs.save(writeOff);

        audit.record(AuditEvent.of(AuditActions.AR_WRITEOFF, "ar_write_offs",
                        writeOff.getId(), writeOff.getUid())
                .detail(Map.of(
                        "arInvoiceUid", inv.getUid(),
                        "amount", faceOutstanding.toPlainString(),
                        "baseRelieved", baseRelieved.toPlainString(),
                        "glEntryUid", posted.uid())));

        return toDto(writeOff);
    }

    @Override
    @Transactional(readOnly = true)
    public ArWriteOffDto getByUid(String uid) {
        ArWriteOff wo = Lookups.orNotFound(writeOffs.findByUid(uid), "ArWriteOff", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), wo.getCompanyId());
        return toDto(wo);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ArWriteOffDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return writeOffs.findByCompanyId(companyId, pageable).map(ArWriteOffServiceImpl::toDto);
    }

    // -------------------------------------------------------------------------

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    private static ArWriteOffDto toDto(ArWriteOff w) {
        return new ArWriteOffDto(
                w.getId(), w.getUid(), w.getCompanyId(), w.getCustomerId(), w.getArInvoiceId(),
                w.getWriteOffDate(), w.getAmount(), w.getCurrency().value(), w.getReason(),
                w.getGlEntryUid());
    }
}
