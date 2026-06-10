package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.ApPaymentDto.PaymentAllocationDto;
import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.PaymentRunRequest;
import com.erp.modules.ap.domain.entity.ApPayment;
import com.erp.modules.ap.domain.entity.ApPaymentAllocation;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.ApPaymentKind;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.ApPaymentAllocationRepository;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDto;
import com.erp.modules.gl.domain.entity.ChartOfAccount;
import com.erp.modules.cashbank.domain.dto.CashAccountGlResolutionDto;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.service.CashBankAccountResolver;
import com.erp.modules.cashbank.service.CashTransactionRecorder;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.repository.SupplierRepository;
import com.erp.modules.tax.domain.dto.WhtCaptureResultDto;
import com.erp.modules.tax.service.WhtCaptureService;
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
 * AP payment engine (ADR-0015 D-3/D-4/D-5).
 *
 * <p>Settles open bills (SELECT FOR UPDATE via repo — no-double-pay, NFR-AP-04).
 * GL: DR Accounts Payable / CR Cash — posted synchronously; GL failure rolls back atomically.
 */
@Service
@Transactional
public class ApPaymentServiceImpl implements ApPaymentService {

    private final SupplierBillRepository        bills;
    private final ApPaymentRepository           payments;
    private final ApPaymentAllocationRepository allocations;
    private final CompanyRepository             companies;
    private final SupplierRepository            suppliers;
    private final ApBillNumberGenerator         numbers;
    private final GLPostingService              glPosting;
    private final GLConfigResolver              glConfig;
    private final CashBankAccountResolver       cashBankAccountResolver;
    private final CashTransactionRecorder       cashTxnRecorder;
    private final WhtCaptureService             whtCapture;
    private final ScopeGuard                    scopeGuard;
    private final AuditService                  audit;

    public ApPaymentServiceImpl(SupplierBillRepository bills,
                                 ApPaymentRepository payments,
                                 ApPaymentAllocationRepository allocations,
                                 CompanyRepository companies,
                                 SupplierRepository suppliers,
                                 ApBillNumberGenerator numbers,
                                 GLPostingService glPosting,
                                 GLConfigResolver glConfig,
                                 CashBankAccountResolver cashBankAccountResolver,
                                 CashTransactionRecorder cashTxnRecorder,
                                 WhtCaptureService whtCapture,
                                 ScopeGuard scopeGuard,
                                 AuditService audit) {
        this.bills                   = bills;
        this.payments                = payments;
        this.allocations             = allocations;
        this.companies               = companies;
        this.suppliers               = suppliers;
        this.numbers                 = numbers;
        this.glPosting               = glPosting;
        this.glConfig                = glConfig;
        this.cashBankAccountResolver = cashBankAccountResolver;
        this.cashTxnRecorder         = cashTxnRecorder;
        this.whtCapture              = whtCapture;
        this.scopeGuard              = scopeGuard;
        this.audit                   = audit;
    }

    // -------------------------------------------------------------------------
    // paySingle
    // -------------------------------------------------------------------------

    @Override
    public ApPaymentDto paySingle(PaySingleBillRequest req) {
        Long companyId = resolveCompany(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // SELECT FOR UPDATE via findOpenByUids — prevents concurrent payment
        List<SupplierBill> open = bills.findOpenByUids(companyId,
                List.of(req.supplierBillUid()));
        if (open.isEmpty()) {
            throw new NotFoundException(
                    "Bill not found or not payable: " + req.supplierBillUid());
        }
        SupplierBill bill = open.get(0);

        BigDecimal toAllocate = req.amount().min(bill.getOutstandingAmount());
        if (toAllocate.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalStateException("Bill " + req.supplierBillUid() + " has zero outstanding.");
        }

        String currency = bill.getCurrency();
        String payNum   = numbers.nextPayment(companyId);

        ApPayment payment = new ApPayment(
                companyId, branchId(), bill.getSupplierId(),
                payNum, ApPaymentKind.SINGLE,
                req.paymentDate(), toAllocate, currency,
                req.tenderType(), req.bankReference(), actorId());
        payment = payments.save(payment);

        // Allocate to bill
        ApPaymentAllocation alloc = new ApPaymentAllocation(
                companyId, payment.getId(), bill.getId(), toAllocate, actorId());
        allocations.save(alloc);

        // Update bill outstanding + status
        bill.setOutstandingAmount(bill.getOutstandingAmount().subtract(toAllocate));
        bill.setStatus(billStatusAfterPayment(bill.getOutstandingAmount()));
        bills.save(bill);

        // GL: DR AP / CR Cash (ADR-0016: cash leg routed via CashBankAccountResolver)
        // ADR-0017 D-9: when WHT present, CR WHT_PAYABLE instead of full cash CR.
        JournalEntryDto posted = postPaymentToGl(payment, companyId, bill.getBranchId(), currency,
                req.cashBankAccountUid(), bill.getSupplierId(), req.whtTypeUid(), req.whtAmount());
        payment.setGlEntryUid(posted.uid());
        payment = payments.save(payment);

        audit.record(AuditEvent.of(AuditActions.AP_PAYMENT_MAKE, "ap_payments",
                        payment.getId(), payment.getUid())
                .detail(Map.of("paymentNumber", payNum,
                        "amount", toAllocate.toPlainString(),
                        "billUid", req.supplierBillUid())));

        List<ApPaymentAllocation> allocList = allocations.findByApPaymentId(payment.getId());
        return toDto(payment, allocList);
    }

    // -------------------------------------------------------------------------
    // paymentRun
    // -------------------------------------------------------------------------

    @Override
    public ApPaymentDto paymentRun(PaymentRunRequest req) {
        Long companyId = resolveCompany(req.companyUid());
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        Long supplierId = null;
        if (req.supplierUid() != null && !req.supplierUid().isBlank()) {
            supplierId = suppliers.findByCompanyIdAndUid(companyId, req.supplierUid())
                    .map(s -> s.getId())
                    .orElseThrow(() -> new NotFoundException("Supplier: " + req.supplierUid()));
        }

        // Select bills (SELECT FOR UPDATE)
        List<SupplierBill> openBills;
        if (req.billUids() != null && !req.billUids().isEmpty()) {
            openBills = bills.findOpenByUids(companyId, req.billUids());
        } else if (supplierId != null) {
            openBills = bills.findOpenForPayment(companyId, supplierId, req.dueOnOrBefore());
        } else {
            openBills = bills.findOpenForPaymentAllSuppliers(companyId, req.dueOnOrBefore());
        }

        if (openBills.isEmpty()) {
            throw new IllegalStateException("No open bills selected by the payment run criteria.");
        }

        // Compute total first so the INSERT never violates chk_ap_payment_amount (amount > 0)
        BigDecimal totalPaid = openBills.stream()
                .map(SupplierBill::getOutstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String currency = openBills.get(0).getCurrency();
        String payNum   = numbers.nextPayment(companyId);

        final Long finalSupplierId = supplierId;
        ApPayment payment = new ApPayment(
                companyId, branchId(), finalSupplierId,
                payNum, ApPaymentKind.PAYMENT_RUN,
                req.paymentDate(), totalPaid, currency,
                req.tenderType(), req.bankReference(), actorId());
        payment = payments.save(payment);

        List<ApPaymentAllocation> allocList = new ArrayList<>();

        for (SupplierBill bill : openBills) {
            BigDecimal toAllocate = bill.getOutstandingAmount();
            ApPaymentAllocation alloc = new ApPaymentAllocation(
                    companyId, payment.getId(), bill.getId(), toAllocate, actorId());
            allocList.add(allocations.save(alloc));

            bill.setOutstandingAmount(BigDecimal.ZERO);
            bill.setStatus(SupplierBillStatus.PAID);
            bills.save(bill);
        }

        // For a payment run the WHT applies to the batch total; supplier id from first bill if batch.
        Long runSupplierId = openBills.isEmpty() ? null : openBills.get(0).getSupplierId();
        JournalEntryDto posted = postPaymentToGl(payment, companyId, branchId(), currency,
                req.cashBankAccountUid(), runSupplierId, req.whtTypeUid(), req.whtAmount());
        payment.setGlEntryUid(posted.uid());
        payment = payments.save(payment);

        audit.record(AuditEvent.of(AuditActions.AP_PAYMENT_MAKE, "ap_payments",
                        payment.getId(), payment.getUid())
                .detail(Map.of("paymentNumber", payNum,
                        "totalPaid", totalPaid.toPlainString(),
                        "billCount", String.valueOf(openBills.size()))));

        return toDto(payment, allocList);
    }

    // -------------------------------------------------------------------------
    // reads
    // -------------------------------------------------------------------------

    @Override
    @Transactional(readOnly = true)
    public ApPaymentDto getByUid(String uid) {
        ApPayment payment = Lookups.orNotFound(payments.findByUid(uid), "ApPayment", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), payment.getCompanyId());
        List<ApPaymentAllocation> allocList = allocations.findByApPaymentId(payment.getId());
        return toDto(payment, allocList);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApPaymentDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return payments.findByCompanyId(companyId, pageable)
                .map(p -> toDto(p, allocations.findByApPaymentId(p.getId())));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApPaymentDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return payments.findByCompanyIdAndSupplierId(companyId, supplierId, pageable)
                .map(p -> toDto(p, allocations.findByApPaymentId(p.getId())));
    }

    // -------------------------------------------------------------------------
    // GL posting
    // -------------------------------------------------------------------------

    private JournalEntryDto postPaymentToGl(ApPayment payment, Long companyId,
                                             Long branchId, String currency,
                                             String cashBankAccountUid,
                                             Long supplierId,
                                             String whtTypeUid, BigDecimal whtAmount) {
        ChartOfAccount apAcct = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_PAYABLE);

        // ADR-0016 D-8: resolve cash/bank account via CashBankAccountResolver (replaces glConfig.CASH)
        CashAccountGlResolutionDto cashRes = cashBankAccountResolver.resolve(companyId, cashBankAccountUid);

        boolean hasWht = whtTypeUid != null
                && whtAmount != null
                && whtAmount.compareTo(BigDecimal.ZERO) > 0;

        // Capture WHT certificate before building the GL draft (ADR-0017 D-9).
        WhtCaptureResultDto whtResult = null;
        if (hasWht) {
            whtResult = whtCapture.captureOnPayment(
                    companyId, branchId,
                    whtTypeUid,
                    supplierId, "Supplier",   // party name resolved from supplier if needed
                    payment.getUid(),
                    payment.getAmount(), whtAmount,
                    currency, payment.getPaymentDate(),
                    null,   // journal entry uid linked after post
                    actorId());
        }

        // DR AP = gross payment amount (bills settled at gross — balanced with cash + WHT legs)
        // CR Cash = gross minus WHT; CR WHT_PAYABLE = WHT (ADR-0017 D-9)
        BigDecimal cashCr = hasWht ? payment.getAmount().subtract(whtAmount) : payment.getAmount();

        List<LineDraft> glLines = new ArrayList<>();
        glLines.add(new LineDraft(apAcct.getId(),
                payment.getAmount(), BigDecimal.ZERO,
                currency, "AP payment — " + payment.getPaymentNumber()));
        glLines.add(new LineDraft(cashRes.glAccountId(),
                BigDecimal.ZERO, cashCr,
                currency, "Cash out — " + payment.getPaymentNumber()));
        if (hasWht) {
            glLines.add(new LineDraft(whtResult.glAccountId(),
                    BigDecimal.ZERO, whtAmount,
                    currency, "WHT payable — " + payment.getPaymentNumber()));
        }

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId, branchId,
                payment.getPaymentDate(),
                "AP Payment " + payment.getPaymentNumber(),
                JournalSourceType.AP_PAYMENT,
                payment.getUid(), null, actorId(), glLines);

        JournalEntryDto posted = glPosting.post(draft);

        // Back-link journal entry uid to WHT transaction (ADR-0017 D-9).
        if (hasWht && whtResult != null) {
            whtCapture.linkJournalEntry(whtResult.whtTransactionUid(), posted.uid());
        }

        // Set the cash/bank account FK on the payment row and record the cash transaction
        payment.setCashBankAccountId(cashRes.cashBankAccountId());
        cashTxnRecorder.recordSettlement(
                companyId, branchId, cashRes.cashBankAccountId(),
                CashTxnType.AP_PAYMENT, CashTxnDirection.OUT,
                payment.getAmount(), currency,
                payment.getUid(), posted.uid(),
                payment.getPaymentDate(), actorId());

        return posted;
    }

    // -------------------------------------------------------------------------

    private static SupplierBillStatus billStatusAfterPayment(BigDecimal outstanding) {
        return outstanding.compareTo(BigDecimal.ZERO) == 0
                ? SupplierBillStatus.PAID
                : SupplierBillStatus.PARTIALLY_PAID;
    }

    private Long resolveCompany(String uid) {
        return companies.findByUid(uid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company: " + uid));
    }

    private Long branchId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.branchId() : null;
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    static ApPaymentDto toDto(ApPayment p, List<ApPaymentAllocation> allocList) {
        List<PaymentAllocationDto> dtoAllocs = allocList.stream()
                .map(a -> new PaymentAllocationDto(
                        a.getId(), a.getSupplierBillId(), null, a.getAllocatedAmount()))
                .toList();
        return new ApPaymentDto(
                p.getId(), p.getUid(), p.getCompanyId(), p.getBranchId(), p.getSupplierId(),
                p.getPaymentNumber(), p.getKind(), p.getPaymentDate(), p.getAmount(),
                p.getCurrency(), p.getTenderType(), p.getBankReference(), p.getGlEntryUid(),
                dtoAllocs);
    }
}
