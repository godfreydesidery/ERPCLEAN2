package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.entity.BillMatch;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.ApDebitNoteAllocationRepository;
import com.erp.modules.ap.repository.ApDebitNoteRepository;
import com.erp.modules.ap.repository.ApPaymentAllocationRepository;
import com.erp.modules.ap.repository.BillMatchRepository;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hard-deletes an unposted supplier bill (AP-01).
 *
 * <p>Hard delete, not a status, on purpose: the schema has no VOID bill status, and the unique key
 * {@code (company_id, supplier_id, supplier_invoice_no)} would block re-entering the corrected
 * invoice behind any soft-deleted row. Nothing about the bill has reached the ledger, the cash book
 * or a supplier statement (those only see MATCHED and later), so removing it loses no accounting
 * record; the audit log keeps who deleted what.
 *
 * <p>Every reference that could point at the bill is checked first and refuses the delete:
 * a GL entry for the bill, payment allocations, debit notes raised against it or applied to it, and
 * a landed-cost charge naming it as its freight bill. The GL and landed-cost checks are scalar JDBC
 * reads — those tables belong to other modules and must not be reached through their entities.
 */
@Service
@Transactional
public class SupplierBillCorrectionServiceImpl implements SupplierBillCorrectionService {

    static final String ALREADY_POSTED =
            "This bill is already in the books, so it cannot be deleted."
                    + " Raise a debit note against it instead.";

    private final SupplierBillRepository          bills;
    private final SupplierBillLineRepository      lines;
    private final BillMatchRepository             matches;
    private final ApPaymentAllocationRepository   paymentAllocations;
    private final ApDebitNoteRepository           debitNotes;
    private final ApDebitNoteAllocationRepository debitNoteAllocations;
    private final JdbcTemplate                    jdbc;
    private final ScopeGuard                      scopeGuard;
    private final AuditService                    audit;

    public SupplierBillCorrectionServiceImpl(SupplierBillRepository bills,
                                             SupplierBillLineRepository lines,
                                             BillMatchRepository matches,
                                             ApPaymentAllocationRepository paymentAllocations,
                                             ApDebitNoteRepository debitNotes,
                                             ApDebitNoteAllocationRepository debitNoteAllocations,
                                             JdbcTemplate jdbc,
                                             ScopeGuard scopeGuard,
                                             AuditService audit) {
        this.bills                = bills;
        this.lines                = lines;
        this.matches              = matches;
        this.paymentAllocations   = paymentAllocations;
        this.debitNotes           = debitNotes;
        this.debitNoteAllocations = debitNoteAllocations;
        this.jdbc                 = jdbc;
        this.scopeGuard           = scopeGuard;
        this.audit                = audit;
    }

    @Override
    public void deleteUnposted(String billUid) {
        SupplierBill bill = Lookups.orNotFound(bills.findByUid(billUid), "SupplierBill", billUid);
        scopeGuard.assertCanActIn(RequestContext.get(), bill.getCompanyId());

        if (bill.getStatus() != SupplierBillStatus.DRAFT
                && bill.getStatus() != SupplierBillStatus.HELD) {
            throw new ConflictException(ALREADY_POSTED);
        }
        if (bill.getPostedGlEntryUid() != null || hasJournalEntry(bill)) {
            throw new ConflictException(ALREADY_POSTED);
        }
        if (!paymentAllocations.findBySupplierBillId(bill.getId()).isEmpty()) {
            throw new ConflictException(
                    "A payment has been recorded against this bill, so it cannot be deleted.");
        }
        if (!debitNoteAllocations.findBySupplierBillId(bill.getId()).isEmpty()
                || debitNotes.existsBySupplierBillId(bill.getId())) {
            throw new ConflictException(
                    "A debit note refers to this bill, so it cannot be deleted.");
        }
        if (isLandedCostFreightBill(bill)) {
            throw new ConflictException("A landed-cost charge uses this bill as its freight bill,"
                    + " so it cannot be deleted.");
        }

        List<BillMatch> matchRows = matches.findBySupplierBillId(bill.getId());
        List<SupplierBillLine> billLines = lines.findBySupplierBillIdOrderByLineNo(bill.getId());

        Map<String, Object> detail = new HashMap<>();
        detail.put("supplierInvoiceNo", bill.getSupplierInvoiceNo());
        detail.put("status", bill.getStatus().name());
        detail.put("grossAmount", bill.getGrossAmount().toPlainString());
        detail.put("supplierId", String.valueOf(bill.getSupplierId()));
        detail.put("lineCount", String.valueOf(billLines.size()));
        if (bill.getBillNumber() != null) {
            detail.put("billNumber", bill.getBillNumber());
        }

        // Children first: bill_match → supplier_bill_lines → supplier_bills (plain FKs, no cascade).
        matches.deleteAll(matchRows);
        matches.flush();
        lines.deleteAll(billLines);
        lines.flush();
        bills.delete(bill);
        bills.flush();

        audit.record(AuditEvent.of(AuditActions.AP_BILL_DELETE, "supplier_bills",
                        bill.getId(), bill.getUid())
                .detail(detail));
    }

    private boolean hasJournalEntry(SupplierBill bill) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM journal_entries "
                        + "WHERE company_id = ? AND source_type = 'AP_BILL' AND source_ref = ?",
                Integer.class, bill.getCompanyId(), bill.getUid());
        return n != null && n > 0;
    }

    private boolean isLandedCostFreightBill(SupplierBill bill) {
        Integer n = jdbc.queryForObject(
                "SELECT COUNT(*) FROM landed_cost_charges WHERE supplier_bill_uid = ?",
                Integer.class, bill.getUid());
        return n != null && n > 0;
    }
}
