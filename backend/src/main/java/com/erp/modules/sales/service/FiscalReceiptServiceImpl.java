package com.erp.modules.sales.service;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.sales.domain.dto.FiscalReceiptDto;
import com.erp.modules.sales.domain.entity.FiscalReceipt;
import com.erp.modules.sales.domain.entity.SalesInvoice;
import com.erp.modules.sales.domain.entity.SalesInvoiceLine;
import com.erp.modules.sales.domain.enums.FiscalReceiptStatus;
import com.erp.modules.sales.domain.enums.InvoiceStatus;
import com.erp.modules.sales.repository.FiscalReceiptRepository;
import com.erp.modules.sales.repository.SalesInvoiceLineRepository;
import com.erp.modules.sales.repository.SalesInvoiceRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.fiscal.FiscalInvoiceDataDto;
import com.erp.platform.fiscal.FiscalisationProvider;
import com.erp.platform.fiscal.FiscalisationResult;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * EFD/fiscal-receipt issue-or-retry orchestration (ADR-0049). Owns {@link SalesInvoiceRepository} /
 * {@link SalesInvoiceLineRepository} reads in-module (never calls into {@code SalesInvoiceService})
 * and never writes to the invoice — fiscalisation is completely decoupled from
 * {@code SalesInvoiceServiceImpl.finalise}.
 *
 * <p>Known constraint (ADR-0049 consequences): the provider call happens synchronously inside this
 * transaction. Fine for the no-I/O {@code NotConfigured}/{@code Simulated} providers; a future real
 * TRA adapter MUST move the network call out of the transaction (claim → call → persist in a second
 * short TX, or route via the outbox/async worker).
 */
@Service
@Transactional
public class FiscalReceiptServiceImpl implements FiscalReceiptService {

    private final FiscalReceiptRepository fiscalReceipts;
    private final SalesInvoiceRepository invoices;
    private final SalesInvoiceLineRepository lines;
    private final CompanyRepository companies;
    private final CustomerRepository customers;
    private final FiscalisationProvider provider;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public FiscalReceiptServiceImpl(FiscalReceiptRepository fiscalReceipts,
                                    SalesInvoiceRepository invoices,
                                    SalesInvoiceLineRepository lines,
                                    CompanyRepository companies,
                                    CustomerRepository customers,
                                    FiscalisationProvider provider,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.fiscalReceipts = fiscalReceipts;
        this.invoices = invoices;
        this.lines = lines;
        this.companies = companies;
        this.customers = customers;
        this.provider = provider;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public FiscalReceiptDto issue(String invoiceUid) {
        SalesInvoice inv = requireInvoice(invoiceUid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());

        if (inv.getStatus() != InvoiceStatus.FINALISED) {
            // ADR-0049 §1: only a FINALISED invoice has the number + frozen totals a fiscal
            // receipt needs; a DRAFT has neither and a VOID must not be (re)fiscalised.
            throw new IllegalStateException(
                    "Only a finalised invoice can be fiscalised. Finalise this invoice first.");
        }

        Optional<FiscalReceipt> existing = fiscalReceipts.findBySalesInvoiceId(inv.getId());
        FiscalReceipt receipt;
        if (existing.isPresent()) {
            receipt = existing.get();
            if (receipt.getStatus() == FiscalReceiptStatus.ISSUED) {
                // ADR-0049 §4: issue is idempotent — an ISSUED receipt is never re-fiscalised.
                return FiscalReceiptDto.from(receipt, inv.getUid());
            }
            if (receipt.getStatus() == FiscalReceiptStatus.VOID) {
                throw new ConflictException(
                        "This invoice's fiscal receipt has been voided and cannot be reissued.");
            }
            // PENDING / FAILED / NOT_CONFIGURED — retry in place on the same row below.
        } else {
            receipt = fiscalReceipts.save(
                    new FiscalReceipt(inv.getCompanyId(), inv.getBranchId(), inv.getId(), actorId()));
        }

        FiscalInvoiceDataDto data = buildInvoiceData(inv);

        audit.record(AuditEvent.of(AuditActions.FISCAL_RECEIPT_ISSUE_ATTEMPT, "fiscal_receipts",
                        receipt.getId(), receipt.getUid())
                .detail(Map.of(
                        "invoiceUid", inv.getUid(),
                        "providerCode", provider.providerCode())));

        FiscalisationResult result = provider.fiscalise(data);
        Instant now = Instant.now();
        Long actor = actorId();

        switch (result.outcome()) {
            case ISSUED -> {
                // issued_at is the device/TRA-reported time when a real adapter supplies it; the
                // NotConfigured/Simulated providers pass null, so fall back to the server clock.
                Instant issuedAt = result.issuedAt() != null ? result.issuedAt() : now;
                receipt.recordIssued(provider.providerCode(), result.fiscalNumber(),
                        result.verificationUrl(), result.signature(), result.deviceSerial(),
                        issuedAt, now, actor);
                audit.record(AuditEvent.of(AuditActions.FISCAL_RECEIPT_ISSUED, "fiscal_receipts",
                                receipt.getId(), receipt.getUid())
                        .detail(Map.of(
                                "invoiceUid", inv.getUid(),
                                "providerCode", provider.providerCode())));
            }
            case NOT_CONFIGURED -> {
                receipt.recordNotConfigured(provider.providerCode(), result.message(), now, actor);
                audit.record(AuditEvent.of(AuditActions.FISCAL_RECEIPT_FAILED, "fiscal_receipts",
                                receipt.getId(), receipt.getUid())
                        .detail(Map.of(
                                "invoiceUid", inv.getUid(),
                                "outcome", "NOT_CONFIGURED")));
            }
            case FAILED -> {
                receipt.recordFailed(provider.providerCode(), result.message(), now, actor);
                audit.record(AuditEvent.of(AuditActions.FISCAL_RECEIPT_FAILED, "fiscal_receipts",
                                receipt.getId(), receipt.getUid())
                        .detail(Map.of(
                                "invoiceUid", inv.getUid(),
                                "outcome", "FAILED")));
            }
        }

        FiscalReceipt saved = fiscalReceipts.save(receipt);
        return FiscalReceiptDto.from(saved, inv.getUid());
    }

    @Override
    @Transactional(readOnly = true)
    public FiscalReceiptDto getForInvoice(String invoiceUid) {
        SalesInvoice inv = requireInvoice(invoiceUid);
        scopeGuard.assertCanActIn(RequestContext.get(), inv.getCompanyId());

        FiscalReceipt receipt = fiscalReceipts.findBySalesInvoiceId(inv.getId())
                .orElseThrow(() -> new NotFoundException("Fiscal receipt not found."));
        return FiscalReceiptDto.from(receipt, inv.getUid());
    }

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    private SalesInvoice requireInvoice(String uid) {
        return Lookups.orNotFound(invoices.findByUid(uid), "SalesInvoice", uid);
    }

    /**
     * Builds the provider-agnostic snapshot from the invoice + its lines + the selling company's
     * tax identity + the buyer's display name. {@code customerTin} is deferred (ADR-0049 §8.9) —
     * always {@code null} in this slice.
     */
    private FiscalInvoiceDataDto buildInvoiceData(SalesInvoice inv) {
        // Self-scope lookup (the invoice's OWN company) — findScopedById, not bare findById
        // (TenantScopingRulesTest).
        Company company = companies.findScopedById(inv.getCompanyId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        // Company-scoped finder (not bare findById) — TenantScopingRulesTest.
        Customer customer = customers.findByCompanyIdAndId(inv.getCompanyId(), inv.getCustomerId())
                .orElseThrow(() -> new NotFoundException("Customer not found."));

        List<SalesInvoiceLine> lineEntities = lines.findByInvoiceIdOrderByLineNo(inv.getId());
        List<FiscalInvoiceDataDto.Line> dataLines = lineEntities.stream()
                .map(l -> new FiscalInvoiceDataDto.Line(
                        l.getProductName(),
                        l.getQuantity(),
                        l.getUnitName(),
                        l.getUnitPriceAmount(),
                        l.getNetAmount(),
                        l.getVatRate().multiply(BigDecimal.valueOf(100)),
                        l.getVatAmount(),
                        l.getVatStatus().name()))
                .toList();

        return new FiscalInvoiceDataDto(
                inv.getUid(),
                inv.getInvoiceNumber(),
                inv.getCompanyId(),
                inv.getBranchId(),
                company.getTaxId(),
                company.getVrn(),
                customer.getDisplayName(),
                null, // customerTin — deferred, ADR-0049 §8.9
                inv.getCurrency().value(),
                inv.getNetTotalAmount(),
                inv.getVatTotalAmount(),
                inv.getGrossTotalAmount(),
                inv.getFinalisedAt(),
                dataLines);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
