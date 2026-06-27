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
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.CurrencyConversionService;
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
    private final CurrencyConversionService fxConverter;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ArOpeningBalanceServiceImpl(ArInvoiceRepository invoices,
                                        CustomerRepository customers,
                                        CompanyRepository companies,
                                        GLPostingService glPosting,
                                        GLConfigResolver glConfig,
                                        CurrencyConversionService fxConverter,
                                        ScopeGuard scopeGuard,
                                        AuditService audit) {
        this.invoices     = invoices;
        this.customers    = customers;
        this.companies    = companies;
        this.glPosting    = glPosting;
        this.glConfig     = glConfig;
        this.fxConverter  = fxConverter;
        this.scopeGuard   = scopeGuard;
        this.audit        = audit;
    }

    @Override
    public ArInvoiceDto setOpeningBalance(SetOpeningBalanceRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Long customerId = customers.findByCompanyIdAndUid(companyId, req.customerUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Customer not found."));

        String currency = req.currency() != null ? req.currency()
                : companies.findById(companyId).map(c -> c.getBaseCurrency())
                        .orElseThrow(() -> new NotFoundException("Company not found."));

        // Guard: amount must be a positive value (null/zero/negative produces a cryptic GL error)
        if (req.amount() == null || req.amount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException(
                    "amount must be a positive value (got: " + req.amount() + ")");
        }

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

        // ADR-0036 D-4 FX triple stamp (fix for I-3/I-4 violations on opening-balance path).
        // CurrencyConversionService short-circuits for base-currency (rate=1, base=face) → no-op (I-5).
        // For a foreign currency: resolves the effective rate on invoiceDate, stamps triple.
        ConvertedAmount fxConv = fxConverter.toBase(req.amount(), currency, companyId, req.invoiceDate());
        inv.setFxRate(fxConv.rate());
        inv.setBaseOriginalAmount(fxConv.baseAmount());
        inv.setBaseOutstandingAmount(fxConv.baseAmount());
        inv.setRateAt(fxConv.rateAt());

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
