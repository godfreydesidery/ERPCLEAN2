package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.BillLineRequest;
import com.erp.modules.ap.domain.dto.EnterBillRequest;
import com.erp.modules.ap.domain.dto.SupplierBillDto;
import com.erp.modules.ap.domain.dto.SupplierBillLineDto;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.entity.SupplierBillLine;
import com.erp.modules.ap.domain.enums.SupplierBillSource;
import com.erp.modules.ap.repository.SupplierBillLineRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
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
 * Enters supplier bills (DRAFT, no GL post) and provides read access (ADR-0015 D-1/D-2/D-3).
 * GL posting happens in BillMatchServiceImpl on match.
 */
@Service
@Transactional
public class SupplierBillServiceImpl implements SupplierBillService {

    private final SupplierBillRepository     bills;
    private final SupplierBillLineRepository lines;
    private final SupplierRepository         suppliers;
    private final CompanyRepository          companies;
    private final ScopeGuard                 scopeGuard;
    private final AuditService               audit;

    public SupplierBillServiceImpl(SupplierBillRepository bills,
                                    SupplierBillLineRepository lines,
                                    SupplierRepository suppliers,
                                    CompanyRepository companies,
                                    ScopeGuard scopeGuard,
                                    AuditService audit) {
        this.bills      = bills;
        this.lines      = lines;
        this.suppliers  = suppliers;
        this.companies  = companies;
        this.scopeGuard = scopeGuard;
        this.audit      = audit;
    }

    @Override
    public SupplierBillDto enterBill(EnterBillRequest req) {
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Long supplierId = suppliers.findByCompanyIdAndUid(companyId, req.supplierUid())
                .map(s -> s.getId())
                .orElseThrow(() -> new NotFoundException("Supplier not found: " + req.supplierUid()));

        // Duplicate-invoice guard (uq_supplier_bill_supplier_invoice)
        if (bills.existsByCompanyIdAndSupplierIdAndSupplierInvoiceNo(
                companyId, supplierId, req.supplierInvoiceNo())) {
            throw new IllegalStateException(
                    "Supplier invoice '" + req.supplierInvoiceNo()
                            + "' already entered for this supplier (duplicate-payable guard).");
        }

        String currency = req.currency() != null ? req.currency()
                : companies.findById(companyId).map(c -> c.getBaseCurrency()).orElse("TZS");

        // Compute net amount from lines
        BigDecimal netAmount = req.lines().stream()
                .map(l -> l.unitCostAmount().multiply(l.billedQty()))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal vatAmount = req.vatAmount() != null ? req.vatAmount() : BigDecimal.ZERO;
        BigDecimal grossAmount = netAmount.add(vatAmount);

        SupplierBill bill = new SupplierBill(
                companyId,
                RequestContext.get() != null ? RequestContext.get().branchId() : null,
                supplierId,
                req.supplierInvoiceNo(),
                SupplierBillSource.BILL,
                req.purchaseOrderUid(),
                req.billDate(),
                req.dueDate(),
                netAmount,
                vatAmount,
                grossAmount,
                currency,
                actorId());
        bill = bills.save(bill);

        // Persist lines
        List<SupplierBillLine> savedLines = new ArrayList<>();
        short lineNo = 1;
        for (BillLineRequest lr : req.lines()) {
            SupplierBillLine line = new SupplierBillLine(
                    bill.getId(), companyId,
                    RequestContext.get() != null ? RequestContext.get().branchId() : null,
                    lineNo++,
                    lr.productId(), lr.poLineUid(), lr.grLineUid(),
                    lr.description(), lr.billedQty(), lr.unitCostAmount(), currency, actorId());
            savedLines.add(lines.save(line));
        }

        audit.record(AuditEvent.of(AuditActions.AP_BILL_ENTER, "supplier_bills",
                        bill.getId(), bill.getUid())
                .detail(Map.of(
                        "supplierInvoiceNo", req.supplierInvoiceNo(),
                        "grossAmount", grossAmount.toPlainString(),
                        "supplierId", String.valueOf(supplierId))));

        return toDto(bill, savedLines);
    }

    @Override
    @Transactional(readOnly = true)
    public SupplierBillDto getByUid(String uid) {
        SupplierBill bill = Lookups.orNotFound(bills.findByUid(uid), "SupplierBill", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), bill.getCompanyId());
        List<SupplierBillLine> billLines = lines.findBySupplierBillIdOrderByLineNo(bill.getId());
        return toDto(bill, billLines);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierBillDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return bills.findByCompanyId(companyId, pageable)
                .map(b -> toDto(b, lines.findBySupplierBillIdOrderByLineNo(b.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupplierBillDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return bills.findByCompanyIdAndSupplierId(companyId, supplierId, pageable)
                .map(b -> toDto(b, lines.findBySupplierBillIdOrderByLineNo(b.getId())));
    }

    // -------------------------------------------------------------------------

    static SupplierBillDto toDto(SupplierBill b, List<SupplierBillLine> lineList) {
        List<SupplierBillLineDto> lineDtos = lineList.stream().map(l ->
                new SupplierBillLineDto(
                        l.getId(), l.getUid(), l.getSupplierBillId(), l.getLineNo(),
                        l.getProductId(), l.getPoLineUid(), l.getGrLineUid(),
                        l.getDescription(), l.getBilledQty(), l.getUnitCostAmount(),
                        l.getLineNetAmount(), l.getCurrency())
        ).toList();
        return new SupplierBillDto(
                b.getId(), b.getUid(), b.getCompanyId(), b.getBranchId(), b.getSupplierId(),
                b.getBillNumber(), b.getSupplierInvoiceNo(), b.getSource(), b.getPurchaseOrderUid(),
                b.getBillDate(), b.getDueDate(),
                b.getNetAmount(), b.getVatAmount(), b.getGrossAmount(), b.getOutstandingAmount(),
                b.getCurrency(), b.getStatus(), b.getPostedGlEntryUid(), lineDtos);
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
