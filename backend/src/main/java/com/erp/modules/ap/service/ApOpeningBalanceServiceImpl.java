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
 *
 * <h2>Why this posts a MATCHED bill the 3-way match never saw (UAT 2026-08-12)</h2>
 *
 * <p>An adversarial read of the AP match hardening flagged this service as a second route to a
 * payable that the match engine never inspects. It was reviewed and it is <b>intentional and
 * correct</b>, for two structural reasons, not a bypass to be closed:
 *
 * <ul>
 *   <li><b>There is nothing to match.</b> An opening balance is what the company already owed on the
 *       day it started using this system. The purchase happened before go-live, so no purchase order
 *       and no goods receipt exist here — or anywhere. A control that compares a bill against
 *       documents that cannot exist has no defensible outcome; holding every opening balance forever
 *       would be the defect.
 *   <li><b>MATCHED is load-bearing, not cosmetic.</b> ADR-0015 D-7 defines the supplier payable as
 *       {@code Σ outstanding_amount WHERE status IN (MATCHED, APPROVED, PARTIALLY_PAID)}, and D-8
 *       makes {@code Σ(sub-ledger) == GL 2100} an invariant that must hold at all times. This method
 *       credits AP in the general ledger; leaving the bill DRAFT or HELD would credit 2100 while
 *       omitting the payable from the sub-ledger total, breaking that reconciliation — the very
 *       "finance-grade defect" FR-AP-08 exists to catch.
 * </ul>
 *
 * <p>The blast radius is bounded by the debit leg, and deliberately so: the offset is
 * {@code OPENING_BALANCE_EQUITY}, never {@code PURCHASES} or {@code INVENTORY}. This path therefore
 * <b>cannot</b> book the cost of an ordinary trade purchase — routing a real supplier invoice
 * through it inflates equity, which a trial-balance review sees immediately, rather than quietly
 * landing in cost of sales. The row also carries {@code source = OPENING_BALANCE} permanently
 * ({@code updatable = false}), so every downstream read can tell these bills apart.
 *
 * <h2>What this method must NOT claim</h2>
 *
 * <p>Two things are deliberately left unwritten, because writing them would manufacture evidence of
 * a control that never ran:
 *
 * <ul>
 *   <li><b>No {@code bill_match} rows.</b> {@code BillMatchStatus} has no honest value for "never
 *       compared" — its four constants are pinned by a frozen DB CHECK, and MATCHED here would state
 *       outright that a line was checked against an order and a receipt. Silence in {@code
 *       bill_match} is the truth; readers must interpret it as "never checked", never as "clean".
 *   <li><b>No {@code matched_at} / {@code matched_by}.</b> Those two columns are the match engine's
 *       audit trail — {@code BillMatchServiceImpl.postMatchedBillToGl} is the only other writer, and
 *       it sets them when a human really ran the comparison. Stamping them here named the
 *       opening-balance clerk as the person who matched the bill, which is simply untrue; they now
 *       stay NULL. The entry is still fully attributable through {@code created_by},
 *       {@code posted_gl_entry_uid} and the append-only {@code AP.OPENING.SET} audit record below,
 *       which states in as many words that no three-way comparison was performed.
 * </ul>
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
            // req.supplierUid() intentionally not surfaced in the user message (error-hygiene rule)
            throw new IllegalStateException(
                    "An opening balance has already been entered for this supplier with invoice ref '"
                            + invoiceNo + "'.");
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
        // MATCHED is required by the D-7 balance definition and the D-8 reconciliation invariant —
        // see the class javadoc. It says "this payable is on the books", NOT "it was compared".
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
        // matched_at / matched_by stay NULL on purpose (UAT 2026-08-12) — they are the 3-way match's
        // audit trail, and no comparison ran here. See the class javadoc.
        bill.setOutstandingAmount(req.grossAmount());
        bill = bills.save(bill);

        // The honest state, recorded where it cannot be edited away: audit_log is append-only, so
        // this is the durable answer to "was this payable ever checked against an order and a
        // receipt?" for a bill that legitimately has no bill_match rows to answer it.
        audit.record(AuditEvent.of(AuditActions.AP_OPENING_SET, "supplier_bills",
                        bill.getId(), bill.getUid())
                .detail(Map.of("supplierId", String.valueOf(supplierId),
                        "grossAmount", req.grossAmount().toPlainString(),
                        "glEntryUid", posted.uid(),
                        "threeWayMatch", "NOT_PERFORMED",
                        "threeWayMatchReason",
                        "Opening balance — pre-go-live liability; no purchase order or goods "
                                + "receipt exists to compare against.")));

        List<SupplierBillLine> savedLines =
                lines.findBySupplierBillIdOrderByLineNo(bill.getId());
        return SupplierBillServiceImpl.toDto(bill, savedLines);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
