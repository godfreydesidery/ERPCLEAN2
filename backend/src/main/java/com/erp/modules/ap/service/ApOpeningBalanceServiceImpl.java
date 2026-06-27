package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.SetApOpeningBalanceRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
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
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * AP opening balance entry (ADR-0015 D-8).
 *
 * <p>Creates an OPENING_BALANCE source bill in MATCHED status, outstanding = grossAmount.
 * GL: DR Opening Balance Equity / CR Accounts Payable — posted synchronously.
 * Idempotency: same supplier + invoice_no combination is rejected (duplicate-payable guard).
 */
@Service
@Transactional
public class ApOpeningBalanceServiceImpl implements ApOpeningBalanceService {

    private static final String OB_INVOICE_PREFIX = "OB-";

    private final SupplierBillRepository     bills;
    private final SupplierBillLineRepository lines;
    private final CompanyRepository          companies;
    private final SupplierRepository         suppliers;
    private final ApBillNumberGenerator      numbers;
    private final GLPostingService           glPosting;
    private final GLConfigResolver           glConfig;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;

    public ApOpeningBalanceServiceImpl(SupplierBillRepository bills,
                                        SupplierBillLineRepository lines,
                                        CompanyRepository companies,
                                        SupplierRepository suppliers,
                                        ApBillNumberGenerator numbers,
                                        GLPostingService glPosting,
                                        GLConfigResolver glConfig,
                                        ScopeGuard scopeGuard,
                                        AuditService audit) {
        this.bills      = bills;
        this.lines      = lines;
        this.companies  = companies;
        this.suppliers  = suppliers;
        this.numbers    = numbers;
        this.glPosting  = glPosting;
        this.glConfig   = glConfig;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public SupplierBillDto setOpeningBalance(SetApOpeningBalanceRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Long supplierId = suppliers.findByCompanyIdAndUid(companyId, req.supplierUid())
                .map(s -> s.getId())
                .orElseThrow(() -> new NotFoundException("Supplier not found."));

        String currency = req.currency() != null && !req.currency().isBlank()
                ? req.currency()
                : companies.findById(companyId).map(c -> c.getBaseCurrency()).orElse("TZS");

        String invoiceNo = req.supplierInvoiceNo() != null && !req.supplierInvoiceNo().isBlank()
                ? req.supplierInvoiceNo()
                : OB_INVOICE_PREFIX + req.supplierUid();

        // Duplicate guard
        if (bills.existsByCompanyIdAndSupplierIdAndSupplierInvoiceNo(companyId, supplierId, invoiceNo)) {
            throw new IllegalStateException(
                    "Opening balance already entered for supplier " + req.supplierUid()
                            + " with invoice ref '" + invoiceNo + "'.");
        }

        SupplierBill bill = new SupplierBill(
                companyId,
                RequestContext.get() != null ? RequestContext.get().branchId() : null,
                supplierId,
                invoiceNo,
                SupplierBillSource.OPENING_BALANCE,
                null,              // no PO
                req.billDate(),
                req.dueDate(),
                req.grossAmount(), // net = gross for OB (no line-level decomposition)
                BigDecimal.ZERO,   // no VAT on OB
                req.grossAmount(),
                currency,
                actorId());

        String billNum = numbers.nextBill(companyId);
        bill.setBillNumber(billNum);
        bill.setStatus(SupplierBillStatus.MATCHED);
        bill = bills.save(bill);

        // Create a single synthetic line (OB bills have no PO/GR refs)
        SupplierBillLine line = new SupplierBillLine(
                bill.getId(), companyId,
                RequestContext.get() != null ? RequestContext.get().branchId() : null,
                (short) 1,
                null, null, null,
                "Opening balance — " + invoiceNo,
                BigDecimal.ONE, req.grossAmount(), currency, actorId());
        lines.save(line);

        // GL: DR Opening Balance Equity / CR AP (D-8/D-6)
        ChartOfAccount obeAcct = glConfig.resolve(companyId, GlConfigKey.OPENING_BALANCE_EQUITY);
        ChartOfAccount apAcct  = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_PAYABLE);

        List<LineDraft> glLines = List.of(
                new LineDraft(obeAcct.getId(),
                        req.grossAmount(), BigDecimal.ZERO,
                        currency, "AP opening balance — " + invoiceNo),
                new LineDraft(apAcct.getId(),
                        BigDecimal.ZERO, req.grossAmount(),
                        currency, "AP opening balance — " + invoiceNo));

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId,
                bill.getBranchId(),
                req.billDate(),
                "AP Opening Balance " + invoiceNo,
                JournalSourceType.AP_BILL,
                bill.getUid(),
                null, actorId(), glLines);

        JournalEntryDto posted = glPosting.post(draft);

        bill.setPostedGlEntryUid(posted.uid());
        bill.setMatchedAt(Instant.now());
        bill.setMatchedBy(actorId());
        bill.setOutstandingAmount(req.grossAmount());
        bill = bills.save(bill);

        audit.record(AuditEvent.of(AuditActions.AP_OPENING_SET, "supplier_bills",
                        bill.getId(), bill.getUid())
                .detail(Map.of("supplierId", String.valueOf(supplierId),
                        "grossAmount", req.grossAmount().toPlainString(),
                        "glEntryUid", posted.uid())));

        List<SupplierBillLine> savedLines =
                lines.findBySupplierBillIdOrderByLineNo(bill.getId());
        return SupplierBillServiceImpl.toDto(bill, savedLines);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
