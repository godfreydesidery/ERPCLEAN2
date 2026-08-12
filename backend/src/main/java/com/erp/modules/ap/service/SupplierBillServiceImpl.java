package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.BillLineRequest;
import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.dto.SupplierBillLineDto;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.domain.enums.BillComparisonState;
import com.erp.modules.ap.domain.enums.DirectReceiptRatificationState;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.gl.repository.ChartOfAccountRepository;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.PaymentTerms;
import com.erp.modules.parties.domain.entity.Supplier;
import com.erp.modules.parties.repository.PaymentTermsRepository;
import com.erp.modules.parties.service.PaymentTermsDueDateCalculator;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.products.domain.enums.VatStatus;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Enters supplier bills (DRAFT, no GL post) and provides read access (ADR-0015 D-1/D-2/D-3).
 * GL posting happens in BillMatchServiceImpl on match.
 */
@Service
@Transactional
public class SupplierBillServiceImpl implements SupplierBillService {

    private final SupplierBillRepository     bills;
    private final SupplierBillLineRepository lines;
    private final SupplierRepository         suppliers;
    private final PaymentTermsRepository     paymentTermsRepo;
    private final CompanyRepository          companies;
    private final ChartOfAccountRepository   chartOfAccounts;
    /** ADR-0041 D4 — reads PO lines via the Purchases service boundary (no entity import, NFR-AP-06). */
    private final PurchaseMatchReader        purchaseMatchReader;
    /** K3 follow-up — surfaces "this receipt still needs ratifying" on every bill read. */
    private final DirectReceiptRatificationGuard ratification;
    /** UAT 2026-08-12 — surfaces how much of the bill was really checked against order + receipt. */
    private final BillComparisonReader       comparisons;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;

    public SupplierBillServiceImpl(SupplierBillRepository bills,
                                    SupplierBillLineRepository lines,
                                    SupplierRepository suppliers,
                                    PaymentTermsRepository paymentTermsRepo,
                                    CompanyRepository companies,
                                    ChartOfAccountRepository chartOfAccounts,
                                    PurchaseMatchReader purchaseMatchReader,
                                    DirectReceiptRatificationGuard ratification,
                                    BillComparisonReader comparisons,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.bills               = bills;
        this.lines               = lines;
        this.suppliers           = suppliers;
        this.paymentTermsRepo    = paymentTermsRepo;
        this.companies           = companies;
        this.chartOfAccounts     = chartOfAccounts;
        this.purchaseMatchReader = purchaseMatchReader;
        this.ratification        = ratification;
        this.comparisons         = comparisons;
        this.scopeGuard          = scopeGuard;
        this.audit               = audit;
    }

    @Override
    public SupplierBillDto enterBill(EnterBillRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Supplier supplier = suppliers.findByCompanyIdAndUid(companyId, req.supplierUid())
                .orElseThrow(() -> new NotFoundException("Supplier not found."));
        Long supplierId = supplier.getId();

        // Cross-supplier PO ownership guard (issue #8): when a PO is referenced the bill's supplier
        // must match the PO's supplier. Reject with ConflictException so the caller gets a clean 409.
        if (req.purchaseOrderUid() != null && !req.purchaseOrderUid().isBlank()) {
            purchaseMatchReader.findPo(req.purchaseOrderUid()).ifPresent(po -> {
                if (!supplierId.equals(po.supplierId())) {
                    // req.purchaseOrderUid() intentionally not surfaced (error-hygiene rule)
                    throw new ConflictException(
                            "The selected purchase order belongs to a different supplier"
                                    + " and cannot be referenced by this bill.");
                }
            });
        }

        // Duplicate-invoice guard (uq_supplier_bill_supplier_invoice)
        if (bills.existsByCompanyIdAndSupplierIdAndSupplierInvoiceNo(
                companyId, supplierId, req.supplierInvoiceNo())) {
            throw new IllegalStateException(
                    "Supplier invoice '" + req.supplierInvoiceNo()
                            + "' already entered for this supplier (duplicate-payable guard).");
        }

        String currency = req.currency() != null ? req.currency()
                : companies.findById(companyId).map(c -> c.getBaseCurrency()).orElse("TZS");

        // Resolve PaymentTerms: request uid > supplier default payment_terms_id (ADR-0041 D1).
        PaymentTerms terms = resolvePaymentTerms(req.paymentTermsUid(), supplier);

        // Derive due date: caller-supplied > PaymentTerms master > paymentTermsDays integer > net-on-receipt (D-2)
        LocalDate dueDate = req.dueDate() != null
                ? req.dueDate()
                : PaymentTermsDueDateCalculator.derive(
                        req.billDate(), terms, supplier.getPaymentTermsDays());

        // Guard: dueDate must not precede billDate (issue #18 — chk_supplier_bill_dates).
        if (dueDate != null && dueDate.isBefore(req.billDate())) {
            throw new IllegalArgumentException(
                    "dueDate (" + dueDate + ") must not be before billDate (" + req.billDate() + ").");
        }

        // Compute net amount from lines and per-line VAT (D-8).
        // lineVatAmount = lineNetAmount × vatRate (zero when vatStatus is null/ZERO_RATED/EXEMPT).
        BigDecimal netAmount     = BigDecimal.ZERO;
        BigDecimal lineVatTotal  = BigDecimal.ZERO;
        for (BillLineRequest lr : req.lines()) {
            BigDecimal lineNet = lr.unitCostAmount().multiply(lr.billedQty());
            netAmount = netAmount.add(lineNet);
            lineVatTotal = lineVatTotal.add(computeLineVat(lineNet, lr.vatStatus(), lr.vatRate()));
        }

        // Header VAT (D-8): when any line carries per-line VAT, the header is the service-enforced
        // Σ of line VAT (mixed-rate bills). Otherwise fall back to the caller-supplied header
        // vatAmount (back-compat with header-level VAT entry). Keeps chk_supplier_bill_amounts satisfied.
        boolean anyLineVat = req.lines().stream()
                .anyMatch(lr -> lr.vatStatus() != null && lr.vatRate() != null);
        BigDecimal vatAmount = anyLineVat
                ? lineVatTotal
                : (req.vatAmount() != null ? req.vatAmount() : BigDecimal.ZERO);
        BigDecimal grossAmount = netAmount.add(vatAmount);

        SupplierBill bill = new SupplierBill(
                companyId,
                RequestContext.get() != null ? RequestContext.get().branchId() : null,
                supplierId,
                req.supplierInvoiceNo(),
                SupplierBillSource.BILL,
                req.purchaseOrderUid(),
                req.billDate(),
                dueDate,
                netAmount,
                vatAmount,
                grossAmount,
                currency,
                actorId());

        // ADR-0041 D1 — stamp payment terms + settlement discount (data-only, no GL leg).
        if (terms != null) {
            bill.setPaymentTermsId(terms.getId());
            bill.setSettlementDiscountDueDate(
                    PaymentTermsDueDateCalculator.settlementDiscountDueDate(req.billDate(), terms));
            bill.setSettlementDiscountAmount(
                    PaymentTermsDueDateCalculator.settlementDiscountAmount(grossAmount, terms));
        }
        bill = bills.save(bill);

        // Persist lines — resolve GL account id from uid when caller provides one (D-8).
        Long branchId = RequestContext.get() != null ? RequestContext.get().branchId() : null;
        List<SupplierBillLine> savedLines = new ArrayList<>();
        short lineNo = 1;
        for (BillLineRequest lr : req.lines()) {
            SupplierBillLine line = new SupplierBillLine(
                    bill.getId(), companyId, branchId,
                    lineNo++,
                    lr.productId(), lr.poLineUid(), lr.grLineUid(),
                    lr.description(), lr.billedQty(), lr.unitCostAmount(), currency, actorId());

            // D-8: stamp per-line VAT fields
            if (lr.vatStatus() != null) {
                line.setVatStatus(lr.vatStatus());
            }
            if (lr.vatRate() != null) {
                line.setVatRate(lr.vatRate());
            }
            BigDecimal lineNet = lr.unitCostAmount().multiply(lr.billedQty());
            line.setLineVatAmount(computeLineVat(lineNet, lr.vatStatus(), lr.vatRate()));

            // D-8: resolve optional GL account override by uid
            if (lr.glAccountUid() != null && !lr.glAccountUid().isBlank()) {
                chartOfAccounts.findByUid(lr.glAccountUid()).ifPresent(
                        acct -> line.setGlAccountId(acct.getId()));
            }

            // ADR-0041 D4: stamp per-line dimension tags (flow onto the GL P&L leg at match-time).
            if (lr.costCentreValueId() != null) {
                line.setCostCentreValueId(lr.costCentreValueId());
            }
            if (lr.departmentValueId() != null) {
                line.setDepartmentValueId(lr.departmentValueId());
            }

            // ADR-0041 D4: when this bill is raised from a PO and the line maps to a PO line, inherit
            // the PO line's dimensions for any tag the caller did NOT explicitly supply. So a PO-line
            // cost centre / department reaches the ledger even when the bill request omits them.
            copyPoLineDimensions(req.purchaseOrderUid(), lr.poLineUid(), line);

            savedLines.add(lines.save(line));
        }

        audit.record(AuditEvent.of(AuditActions.AP_BILL_ENTER, "supplier_bills",
                        bill.getId(), bill.getUid())
                .detail(Map.of(
                        "supplierInvoiceNo", req.supplierInvoiceNo(),
                        "grossAmount", grossAmount.toPlainString(),
                        "supplierId", String.valueOf(supplierId))));

        // A bill just entered cannot have been matched yet — there are no bill_match rows behind it,
        // and saying so is the honest answer rather than a query that can only return one value.
        return toDto(bill, savedLines, ratification.stateFor(bill.getPurchaseOrderUid()),
                BillComparisonState.NEVER_MATCHED);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierBillDto getByUid(String uid) {
        SupplierBill bill = Lookups.orNotFound(bills.findByUid(uid), "SupplierBill", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), bill.getCompanyId());
        List<SupplierBillLine> billLines = lines.findBySupplierBillIdOrderByLineNo(bill.getId());
        return toDto(bill, billLines, ratification.stateFor(bill.getPurchaseOrderUid()),
                comparisons.stateFor(bill.getId(), billLines.size()));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierBillDto> listByCompany(Long companyId, Pageable pageable) {
        return searchInternal(companyId, null, null, null, false, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierBillDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable) {
        return searchInternal(companyId, supplierId, null, null, false, pageable);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierBillDto> search(Long companyId,
                                        Long supplierId,
                                        String supplierUid,
                                        SupplierBillStatus status,
                                        boolean uncomparedOnly,
                                        Pageable pageable) {
        return searchInternal(companyId, supplierId, supplierUid, status, uncomparedOnly, pageable);
    }

    private Page<SupplierBillDto> searchInternal(Long companyId,
                                                 Long supplierId,
                                                 String supplierUid,
                                                 SupplierBillStatus status,
                                                 boolean uncomparedOnly,
                                                 Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Long resolvedSupplierId = resolveSupplierId(companyId, supplierId, supplierUid);
        // A supplier uid that resolves to nothing must narrow to nothing, not silently widen to
        // "every bill in the company" — a filter that quietly stops filtering is how a reviewer
        // ends up certain they looked at a supplier they never saw.
        if (resolvedSupplierId == null && supplierUid != null && !supplierUid.isBlank()) {
            return Page.empty(pageable);
        }
        return toDtoPage(bills.search(companyId, resolvedSupplierId, status,
                uncomparedOnly ? Boolean.TRUE : null, pageable));
    }

    /**
     * Resolves which supplier to filter on: an explicit numeric id wins, else the uid is looked up
     * WITHIN the company (never across it — the company must come from the scope-checked value, not
     * from whatever the uid happens to belong to). Returns null when neither was supplied.
     */
    private Long resolveSupplierId(Long companyId, Long supplierId, String supplierUid) {
        if (supplierId != null) {
            return supplierId;
        }
        if (supplierUid == null || supplierUid.isBlank()) {
            return null;
        }
        return suppliers.findByCompanyIdAndUid(companyId, supplierUid.trim())
                .map(Supplier::getId)
                .orElse(null);
    }

    // -------------------------------------------------------------------------

    /**
     * Maps one page of bills to DTOs, resolving the direct-receipt ratification state for the whole
     * page in a single purchase-order read.
     *
     * <p>Two things this must NOT do, both learned the hard way. It must not reach the state through
     * the Purchases detail read: that read reconciles the order against the approval engine and
     * writes the result, and these list methods are {@code readOnly}, where Hibernate's MANUAL flush
     * mode discards the write without a sound. And it must not resolve one bill at a time: that is
     * an approval-engine round-trip per row. {@link DirectReceiptRatificationGuard#snapshotFor} does
     * both correctly — no writes, one call for the page — and answers NOT_APPLICABLE for anything it
     * could not resolve, so a lookup that misbehaves costs a badge, never the page.
     */
    private Page<SupplierBillDto> toDtoPage(Page<SupplierBill> page) {
        DirectReceiptRatificationGuard.Snapshot ratificationStates = ratification.snapshotFor(
                page.getContent().stream()
                        .map(SupplierBill::getPurchaseOrderUid)
                        .filter(uid -> uid != null && !uid.isBlank())
                        .distinct()
                        .toList());
        // Same discipline for the comparison signal: one query for the page, resolved up front.
        BillComparisonReader.Snapshot comparisonStates = comparisons.snapshotFor(
                page.getContent().stream().map(SupplierBill::getId).toList());
        return page.map(b -> {
            List<SupplierBillLine> billLines = lines.findBySupplierBillIdOrderByLineNo(b.getId());
            return toDto(b, billLines,
                    ratificationStates.stateFor(b.getPurchaseOrderUid()),
                    comparisonStates.stateFor(b.getId(), billLines.size()));
        });
    }

    /**
     * Overload for callers with no backing purchase order and no match run behind the bill — today
     * that is AP opening balances, which are stamped MATCHED and post their own journal entry
     * without the match engine ever seeing them.
     *
     * <p>{@link BillComparisonState#NEVER_MATCHED} is therefore the literal truth for such a bill,
     * not a default: no {@code bill_match} row exists, so nothing about it was ever compared against
     * an order or a receipt. Reporting that plainly is the whole point — an opening balance that
     * came back "compared" would be the signal certifying exactly the payables nobody checked.
     */
    static SupplierBillDto toDto(SupplierBill b, List<SupplierBillLine> lineList) {
        return toDto(b, lineList, DirectReceiptRatificationState.NOT_APPLICABLE,
                BillComparisonState.NEVER_MATCHED);
    }

    static SupplierBillDto toDto(SupplierBill b, List<SupplierBillLine> lineList,
                                  DirectReceiptRatificationState ratificationState,
                                  BillComparisonState comparisonState) {
        List<SupplierBillLineDto> lineDtos = lineList.stream().map(l ->
                new SupplierBillLineDto(
                        l.getId(), l.getUid(), l.getSupplierBillId(), l.getLineNo(),
                        l.getProductId(), l.getPoLineUid(), l.getGrLineUid(),
                        l.getDescription(), l.getBilledQty(), l.getUnitCostAmount(),
                        l.getLineNetAmount(), l.getCurrency().value(),
                        // D-8: per-line VAT + GL override
                        l.getVatStatus(), l.getVatRate(),
                        l.getLineVatAmount() != null ? l.getLineVatAmount() : BigDecimal.ZERO,
                        l.getGlAccountId(),
                        // P2: per-line dimensions
                        l.getCostCentreValueId(), l.getDepartmentValueId())
        ).toList();
        return new SupplierBillDto(
                b.getId(), b.getUid(), b.getCompanyId(), b.getBranchId(), b.getSupplierId(),
                b.getBillNumber(), b.getSupplierInvoiceNo(), b.getSource(), b.getPurchaseOrderUid(),
                b.getBillDate(), b.getDueDate(),
                // P2: tax-point + received dates
                b.getTaxPointDate(), b.getReceivedDate(),
                // P2 D1: payment terms + settlement discount
                b.getPaymentTermsId(), b.getSettlementDiscountDueDate(), b.getSettlementDiscountAmount(),
                b.getNetAmount(), b.getVatAmount(), b.getGrossAmount(), b.getOutstandingAmount(),
                b.getCurrency().value(), b.getStatus(), b.getPostedGlEntryUid(),
                // D-7: WHT snapshot
                b.getWhtTypeId(), b.getWhtTaxableBase(), b.getWhtAmount(),
                // K3 follow-up: derived ratification state of the backing direct receipt
                ratificationState != null
                        ? ratificationState : DirectReceiptRatificationState.NOT_APPLICABLE,
                // UAT 2026-08-12: derived comparison state. A missing value falls back to
                // NEVER_MATCHED, never to "compared" — an unknown must never read as verified.
                comparisonState != null ? comparisonState : BillComparisonState.NEVER_MATCHED,
                lineDtos);
    }

    /**
     * Computes line VAT amount = lineNet × vatRate.
     * Returns ZERO when vatStatus is null, ZERO_RATED, or EXEMPT, or when vatRate is null/zero.
     * Back-compat: callers that omit vatStatus/vatRate get ZERO (no VAT applied).
     */
    static BigDecimal computeLineVat(BigDecimal lineNet, VatStatus vatStatus, BigDecimal vatRate) {
        if (vatStatus == null
                || vatStatus == VatStatus.ZERO_RATED
                || vatStatus == VatStatus.EXEMPT
                || vatRate == null
                || vatRate.compareTo(BigDecimal.ZERO) == 0) {
            return BigDecimal.ZERO;
        }
        return lineNet.multiply(vatRate).setScale(4, RoundingMode.HALF_UP);
    }

    /**
     * Resolves the PaymentTerms master for a bill: the request uid takes priority, falling back to
     * the supplier's default {@code payment_terms_id} (ADR-0041 D1). Returns null when neither
     * resolves (the calculator then falls back to the integer paymentTermsDays / net-on-receipt).
     */
    private PaymentTerms resolvePaymentTerms(String paymentTermsUid, Supplier supplier) {
        if (paymentTermsUid != null && !paymentTermsUid.isBlank()) {
            PaymentTerms pt = paymentTermsRepo.findByUid(paymentTermsUid).orElse(null);
            if (pt != null) {
                return pt;
            }
        }
        if (supplier.getPaymentTermsId() != null) {
            return paymentTermsRepo.findById(supplier.getPaymentTermsId()).orElse(null);
        }
        return null;
    }

    /**
     * ADR-0041 D4 — copies the source PO line's cost-centre / department onto a derived bill line.
     * Only runs when the bill is raised from a PO ({@code purchaseOrderUid} set) AND the bill line
     * maps to a PO line ({@code poLineUid} set). Caller-supplied tags WIN — only an unset tag is
     * inherited. Reads the PO line via the Purchases service boundary (no entity import). Fail-open:
     * any lookup miss leaves the bill line untagged (a missing dimension never blocks bill entry).
     */
    private void copyPoLineDimensions(String purchaseOrderUid, String poLineUid, SupplierBillLine line) {
        if (purchaseOrderUid == null || purchaseOrderUid.isBlank()
                || poLineUid == null || poLineUid.isBlank()) {
            return;
        }
        // Nothing to inherit if the caller already tagged both dimensions.
        if (line.getCostCentreValueId() != null && line.getDepartmentValueId() != null) {
            return;
        }
        purchaseMatchReader.findPoLine(purchaseOrderUid, poLineUid).ifPresent(poLine -> {
            if (line.getCostCentreValueId() == null && poLine.costCentreValueId() != null) {
                line.setCostCentreValueId(poLine.costCentreValueId());
            }
            if (line.getDepartmentValueId() == null && poLine.departmentValueId() != null) {
                line.setDepartmentValueId(poLine.departmentValueId());
            }
        });
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
