package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArInvoiceDto;
import com.erp.modules.ar.domain.dto.SetOpeningBalanceRequest;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.enums.ArInvoiceSource;
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
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enters an AR opening balance (FR-AR-15). Creates an open item (source=OPENING_BALANCE) and
 * posts DR AR control / CR Opening Balance Equity synchronously (ADR-0014 D-4/D-6).
 */
@Service
@Transactional
public class ArOpeningBalanceServiceImpl implements ArOpeningBalanceService {

    private final ArInvoiceRepository invoices;
    private final CustomerRepository customers;
    private final CompanyRepository companies;
    private final GLPostingService glPosting;
    private final GLConfigResolver glConfig;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ArOpeningBalanceServiceImpl(ArInvoiceRepository invoices,
                                        CustomerRepository customers,
                                        CompanyRepository companies,
                                        GLPostingService glPosting,
                                        GLConfigResolver glConfig,
                                        ScopeGuard scopeGuard,
                                        AuditService audit) {
        this.invoices   = invoices;
        this.customers  = customers;
        this.companies  = companies;
        this.glPosting  = glPosting;
        this.glConfig   = glConfig;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public ArInvoiceDto setOpeningBalance(SetOpeningBalanceRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Long customerId = customers.findByCompanyIdAndUid(companyId, req.customerUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Customer not found: " + req.customerUid()));

        String currency = req.currency() != null ? req.currency()
                : companies.findById(companyId).map(c -> c.getBaseCurrency()).orElse("TZS");

        // Post DR AR control / CR Opening Balance Equity synchronously (D-4/D-6)
        ChartOfAccount arAcct     = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE);
        ChartOfAccount equityAcct = glConfig.resolve(companyId, GlConfigKey.OPENING_BALANCE_EQUITY);

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId,
                null, // opening balances have no branch tag
                req.invoiceDate(),
                "AR Opening Balance — " + (req.documentNo() != null ? req.documentNo() : ""),
                JournalSourceType.OPENING_BALANCE,
                null,
                null,
                actorId(),
                List.of(
                        new LineDraft(arAcct.getId(), req.amount(), BigDecimal.ZERO, currency,
                                "AR opening balance"),
                        new LineDraft(equityAcct.getId(), BigDecimal.ZERO, req.amount(), currency,
                                "Opening balance equity contra")
                ));

        JournalEntryDto posted = glPosting.post(draft);

        // Create the open item (no GL post from AR — the post above IS the GL entry)
        ArInvoice inv = new ArInvoice(
                companyId, null, customerId,
                ArInvoiceSource.OPENING_BALANCE, null, req.documentNo(),
                req.amount(), currency, req.invoiceDate(), req.dueDate(), actorId());
        inv = invoices.save(inv);

        audit.record(AuditEvent.of(AuditActions.AR_OPENING_SET, "ar_invoices",
                        inv.getId(), inv.getUid())
                .detail(Map.of(
                        "amount", req.amount().toPlainString(),
                        "customerId", String.valueOf(customerId),
                        "glEntryUid", posted.uid())));

        return ArInvoiceServiceImpl.toDto(inv);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
