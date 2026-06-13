package com.erp.modules.ar.service;

import com.erp.modules.ar.domain.dto.ArReceiptDto;
import com.erp.modules.ar.domain.dto.ArReceiptDto.AllocationDto;
import com.erp.modules.ar.domain.dto.PaymentReceivedPayload;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest;
import com.erp.modules.ar.domain.dto.RecordReceiptRequest.AllocationLineRequest;
import com.erp.modules.ar.domain.entity.ArInvoice;
import com.erp.modules.ar.domain.entity.ArReceipt;
import com.erp.modules.ar.domain.entity.ArReceiptAllocation;
import com.erp.modules.ar.domain.enums.ArInvoiceStatus;
import com.erp.modules.ar.domain.enums.ArReceiptStatus;
import com.erp.modules.ar.repository.ArInvoiceRepository;
import com.erp.modules.ar.repository.ArReceiptAllocationRepository;
import com.erp.modules.ar.repository.ArReceiptRepository;
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
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.tax.domain.dto.WhtCaptureResultDto;
import com.erp.modules.tax.service.WhtCaptureService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records AR receipts, allocates them (oldest-first or manual override), and posts the cash leg
 * to GL synchronously in the same transaction (ADR-0014 D-3/D-4, FR-AR-06/07, BR-AR-04/05/12).
 *
 * <p>A GL failure (missing config, closed period) rolls back the whole command — the receipt is
 * not created and the sub-ledger is untouched. This is the correct atomicity (D-4).
 */
@Service
@Transactional
public class ArReceiptServiceImpl implements ArReceiptService {

    private final ArReceiptRepository receipts;
    private final ArInvoiceRepository invoices;
    private final ArReceiptAllocationRepository allocations;
    private final CustomerRepository customers;
    private final CompanyRepository companies;
    private final ArReceiptNumberGenerator numberGen;
    private final GLPostingService glPosting;
    private final GLConfigResolver glConfig;
    private final CashBankAccountResolver cashBankAccountResolver;
    private final CashTransactionRecorder cashTxnRecorder;
    private final WhtCaptureService whtCapture;
    private final OutboxPublisher outbox;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ArReceiptServiceImpl(ArReceiptRepository receipts,
                                 ArInvoiceRepository invoices,
                                 ArReceiptAllocationRepository allocations,
                                 CustomerRepository customers,
                                 CompanyRepository companies,
                                 ArReceiptNumberGenerator numberGen,
                                 GLPostingService glPosting,
                                 GLConfigResolver glConfig,
                                 CashBankAccountResolver cashBankAccountResolver,
                                 CashTransactionRecorder cashTxnRecorder,
                                 WhtCaptureService whtCapture,
                                 OutboxPublisher outbox,
                                 ScopeGuard scopeGuard,
                                 AuditService audit) {
        this.receipts                = receipts;
        this.invoices                = invoices;
        this.allocations             = allocations;
        this.customers               = customers;
        this.companies               = companies;
        this.numberGen               = numberGen;
        this.glPosting               = glPosting;
        this.glConfig                = glConfig;
        this.cashBankAccountResolver = cashBankAccountResolver;
        this.cashTxnRecorder         = cashTxnRecorder;
        this.whtCapture              = whtCapture;
        this.outbox                  = outbox;
        this.scopeGuard              = scopeGuard;
        this.audit                   = audit;
    }

    @Override
    public ArReceiptDto recordAndAllocate(RecordReceiptRequest req) {
        // 1. Resolve company and scope guard
        Long companyId = companies.findByUid(req.companyUid())
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found: " + req.companyUid()));
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        // 2. Resolve customer (must belong to the company)
        Customer customer = customers.findByCompanyIdAndUid(companyId, req.customerUid())
                .orElseThrow(() -> new NotFoundException("Customer not found: " + req.customerUid()));

        String currency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElse("TZS");

        // 3. Generate receipt number
        String receiptNumber = numberGen.nextReceipt(companyId);

        // 4. Create the receipt header (unallocated_amount starts == amount)
        ArReceipt receipt = new ArReceipt(
                companyId,
                RequestContext.get() != null ? RequestContext.get().branchId() : null,
                customer.getId(),
                receiptNumber,
                req.receiptDate(),
                req.amount(),
                currency,
                req.tenderType(),
                actorId());
        receipt = receipts.save(receipt);

        // 5. Build allocation set
        List<ArReceiptAllocation> allocationList;
        if (req.allocations() == null || req.allocations().isEmpty()) {
            // Oldest-first auto-allocation (BR-AR-03)
            allocationList = autoAllocate(receipt, companyId, customer.getId());
        } else {
            // Manual override (BR-AR-03)
            allocationList = manualAllocate(receipt, companyId, req.allocations());
        }

        // 6. Apply allocation — reduce open items, update receipt unallocated_amount
        BigDecimal totalAllocated = BigDecimal.ZERO;
        List<ArReceiptAllocation> savedAllocs = new ArrayList<>();
        for (ArReceiptAllocation alloc : allocationList) {
            ArInvoice inv = invoices.findById(alloc.getArInvoiceId())
                    .orElseThrow(() -> new NotFoundException("ArInvoice not found: " + alloc.getArInvoiceId()));
            // Guard: allocation must not exceed current outstanding
            if (alloc.getAllocatedAmount().compareTo(inv.getOutstandingAmount()) > 0) {
                throw new IllegalStateException(
                        "Allocation " + alloc.getAllocatedAmount()
                                + " exceeds outstanding " + inv.getOutstandingAmount()
                                + " on invoice uid=" + inv.getUid() + " (BR-AR-04).");
            }
            inv.setOutstandingAmount(inv.getOutstandingAmount().subtract(alloc.getAllocatedAmount()));
            inv.setStatus(deriveInvoiceStatus(inv.getOutstandingAmount(), inv.getOriginalAmount()));
            inv.setUpdatedAt(Instant.now());
            inv.setUpdatedBy(actorId());
            invoices.save(inv);

            savedAllocs.add(allocations.save(alloc));
            totalAllocated = totalAllocated.add(alloc.getAllocatedAmount());
        }

        // 7. Guard: total allocated must not exceed receipt amount (BR-AR-04)
        if (totalAllocated.compareTo(receipt.getAmount()) > 0) {
            throw new IllegalStateException(
                    "Total allocated " + totalAllocated
                            + " exceeds receipt amount " + receipt.getAmount() + " (BR-AR-04).");
        }

        // 8. Update receipt unallocated_amount and status
        BigDecimal unallocated = receipt.getAmount().subtract(totalAllocated);
        receipt.setUnallocatedAmount(unallocated);
        receipt.setStatus(deriveReceiptStatus(unallocated, receipt.getAmount(), totalAllocated));
        receipt.setUpdatedAt(Instant.now());
        receipt.setUpdatedBy(actorId());

        // 9. Post cash leg to GL synchronously (D-4). Failure rolls back the whole TX.
        //    ADR-0016: resolve cash/bank account via CashBankAccountResolver (replaces glConfig.CASH).
        //    ADR-0017 D-9: if WHT present, capture certificate first to get GL account, then reduce
        //    the cash DR leg by whtAmount and append DR WHT_RECEIVABLE whtAmount.
        CashAccountGlResolutionDto cashRes = cashBankAccountResolver.resolve(
                companyId, req.cashBankAccountUid());
        ChartOfAccount arAcct = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE);

        boolean hasWht = req.whtTypeUid() != null
                && req.whtAmount() != null
                && req.whtAmount().compareTo(BigDecimal.ZERO) > 0;

        // Capture WHT certificate before building the draft so we have glAccountId.
        WhtCaptureResultDto whtResult = null;
        if (hasWht) {
            whtResult = whtCapture.captureOnReceipt(
                    companyId, receipt.getBranchId(),
                    req.whtTypeUid(),
                    customer.getId(), customer.getDisplayName(),
                    receipt.getUid(),
                    receipt.getAmount(), req.whtAmount(),
                    currency, receipt.getReceiptDate(),
                    null, // journal entry uid linked after post
                    actorId());
        }

        List<LineDraft> glLines = new ArrayList<>();
        // Cash DR = full amount minus any WHT withheld by customer
        BigDecimal cashDr = hasWht ? receipt.getAmount().subtract(req.whtAmount()) : receipt.getAmount();
        glLines.add(new LineDraft(cashRes.glAccountId(), cashDr, BigDecimal.ZERO, currency,
                "Cash received from " + customer.getDisplayName()));
        // WHT_RECEIVABLE DR leg (the WHT the customer held back on our behalf)
        if (hasWht) {
            glLines.add(new LineDraft(whtResult.glAccountId(), req.whtAmount(), BigDecimal.ZERO, currency,
                    "WHT receivable — " + receiptNumber));
        }
        // AR CR = full settled amount (cashDr + whtAmount = receipt.amount — balanced)
        glLines.add(new LineDraft(arAcct.getId(), BigDecimal.ZERO, receipt.getAmount(), currency,
                "AR control — " + receiptNumber));

        JournalEntryDraft draft = new JournalEntryDraft(
                companyId,
                receipt.getBranchId(),
                receipt.getReceiptDate(),
                "AR Receipt " + receiptNumber + " — " + customer.getDisplayName(),
                JournalSourceType.AR_RECEIPT,
                receipt.getUid(),
                null,
                actorId(),
                glLines);

        JournalEntryDto posted = glPosting.post(draft);
        receipt.setGlEntryUid(posted.uid());
        receipt.setCashBankAccountId(cashRes.cashBankAccountId());
        receipt = receipts.save(receipt);

        // 9b. Link journal entry uid back to WHT transaction (ADR-0017 D-9).
        if (hasWht && whtResult != null) {
            whtCapture.linkJournalEntry(whtResult.whtTransactionUid(), posted.uid());
        }

        // 9c. Append cash_transaction row for this settlement (ADR-0016 D-13).
        cashTxnRecorder.recordSettlement(
                companyId, receipt.getBranchId(), cashRes.cashBankAccountId(),
                CashTxnType.AR_RECEIPT, CashTxnDirection.IN,
                receipt.getAmount(), currency,
                receipt.getUid(), posted.uid(),
                receipt.getReceiptDate(), actorId());

        // 10. Audit
        audit.record(AuditEvent.of(AuditActions.AR_RECEIPT_RECORD, "ar_receipts",
                        receipt.getId(), receipt.getUid())
                .detail(Map.of(
                        "receiptNumber", receiptNumber,
                        "amount", receipt.getAmount().toPlainString(),
                        "customerId", String.valueOf(customer.getId()),
                        "glEntryUid", posted.uid())));

        // 11. Payment notification trigger — PAYMENT.RECEIVED (ADR-0024 D-8).
        // BR-NOTIF-13: amountFormatted must be a pre-formatted display string (e.g. "TZS 1,250.00"),
        // never a raw BigDecimal string. DecimalFormat is not thread-safe; create a new instance here.
        String amountFormatted = (currency != null ? currency + " " : "")
                + new DecimalFormat("#,##0.00").format(receipt.getAmount());
        outbox.publish(DomainEventType.PAYMENT_RECEIVED, DomainEventType.AGG_AR_RECEIPT,
                receipt.getId(), receipt.getUid(), companyId, receipt.getBranchId(),
                new PaymentReceivedPayload(receipt.getUid(), companyId, receipt.getBranchId(),
                        customer.getDisplayName(), amountFormatted,
                        currency, Instant.now()));

        return toDto(receipt, savedAllocs, invoices);
    }

    @Override
    public ArReceiptDto reallocate(String receiptUid, List<AllocationLineRequest> newAllocations) {
        ArReceipt receipt = Lookups.orNotFound(receipts.findByUid(receiptUid), "ArReceipt", receiptUid);
        scopeGuard.assertCanActIn(RequestContext.get(), receipt.getCompanyId());

        // Restore outstanding on currently allocated invoices
        List<ArReceiptAllocation> existing = allocations.findByReceiptId(receipt.getId());
        for (ArReceiptAllocation old : existing) {
            invoices.findById(old.getArInvoiceId()).ifPresent(inv -> {
                inv.setOutstandingAmount(inv.getOutstandingAmount().add(old.getAllocatedAmount()));
                inv.setStatus(deriveInvoiceStatus(inv.getOutstandingAmount(), inv.getOriginalAmount()));
                inv.setUpdatedAt(Instant.now());
                inv.setUpdatedBy(actorId());
                invoices.save(inv);
            });
        }
        allocations.deleteByReceiptId(receipt.getId());
        allocations.flush();

        // Apply new allocation set
        BigDecimal totalAllocated = BigDecimal.ZERO;
        List<ArReceiptAllocation> saved = new ArrayList<>();
        for (AllocationLineRequest line : newAllocations) {
            ArInvoice inv = invoices.findByCompanyIdAndUid(receipt.getCompanyId(), line.arInvoiceUid())
                    .orElseThrow(() -> new NotFoundException("ArInvoice not found: " + line.arInvoiceUid()));
            if (line.allocatedAmount().compareTo(inv.getOutstandingAmount()) > 0) {
                throw new IllegalStateException(
                        "Re-allocation " + line.allocatedAmount()
                                + " exceeds outstanding on invoice " + line.arInvoiceUid());
            }
            inv.setOutstandingAmount(inv.getOutstandingAmount().subtract(line.allocatedAmount()));
            inv.setStatus(deriveInvoiceStatus(inv.getOutstandingAmount(), inv.getOriginalAmount()));
            inv.setUpdatedAt(Instant.now());
            inv.setUpdatedBy(actorId());
            invoices.save(inv);

            saved.add(allocations.save(new ArReceiptAllocation(
                    receipt.getCompanyId(), receipt.getId(), inv.getId(),
                    line.allocatedAmount(), actorId())));
            totalAllocated = totalAllocated.add(line.allocatedAmount());
        }
        if (totalAllocated.compareTo(receipt.getAmount()) > 0) {
            throw new IllegalStateException(
                    "Re-allocation total " + totalAllocated
                            + " exceeds receipt amount " + receipt.getAmount() + " (BR-AR-04).");
        }

        BigDecimal unallocated = receipt.getAmount().subtract(totalAllocated);
        receipt.setUnallocatedAmount(unallocated);
        receipt.setStatus(deriveReceiptStatus(unallocated, receipt.getAmount(), totalAllocated));
        receipt.setUpdatedAt(Instant.now());
        receipt.setUpdatedBy(actorId());
        receipt = receipts.save(receipt);

        audit.record(AuditEvent.of(AuditActions.AR_RECEIPT_ALLOCATE, "ar_receipts",
                        receipt.getId(), receipt.getUid())
                .detail(Map.of("action", "reallocate")));

        return toDto(receipt, saved, invoices);
    }

    @Override
    @Transactional(readOnly = true)
    public ArReceiptDto getByUid(String uid) {
        ArReceipt receipt = Lookups.orNotFound(receipts.findByUid(uid), "ArReceipt", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), receipt.getCompanyId());
        List<ArReceiptAllocation> allocs = allocations.findByReceiptId(receipt.getId());
        return toDto(receipt, allocs, invoices);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ArReceiptDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return receipts.findByCompanyId(companyId, pageable)
                .map(r -> toDto(r, allocations.findByReceiptId(r.getId()), invoices));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ArReceiptDto> listByCustomer(Long companyId, Long customerId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return receipts.findByCompanyIdAndCustomerId(companyId, customerId, pageable)
                .map(r -> toDto(r, allocations.findByReceiptId(r.getId()), invoices));
    }

    // -------------------------------------------------------------------------
    // Allocation helpers
    // -------------------------------------------------------------------------

    private List<ArReceiptAllocation> autoAllocate(ArReceipt receipt,
                                                    Long companyId, Long customerId) {
        List<ArInvoice> openItems =
                invoices.findOpenForUpdateByCompanyAndCustomer(companyId, customerId);
        List<ArReceiptAllocation> result = new ArrayList<>();
        BigDecimal remaining = receipt.getAmount();

        for (ArInvoice inv : openItems) {
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) break;
            BigDecimal slice = remaining.min(inv.getOutstandingAmount());
            if (slice.compareTo(BigDecimal.ZERO) > 0) {
                result.add(new ArReceiptAllocation(
                        companyId, receipt.getId(), inv.getId(), slice, actorId()));
                remaining = remaining.subtract(slice);
            }
        }
        return result;
    }

    private List<ArReceiptAllocation> manualAllocate(ArReceipt receipt,
                                                      Long companyId,
                                                      List<AllocationLineRequest> lines) {
        List<ArReceiptAllocation> result = new ArrayList<>();
        for (AllocationLineRequest line : lines) {
            ArInvoice inv = invoices.findByCompanyIdAndUid(companyId, line.arInvoiceUid())
                    .orElseThrow(() -> new NotFoundException(
                            "ArInvoice not found: " + line.arInvoiceUid()));
            result.add(new ArReceiptAllocation(
                    companyId, receipt.getId(), inv.getId(),
                    line.allocatedAmount(), actorId()));
        }
        return result;
    }

    // -------------------------------------------------------------------------
    // Status derivation helpers (D-3)
    // -------------------------------------------------------------------------

    private static ArInvoiceStatus deriveInvoiceStatus(BigDecimal outstanding, BigDecimal original) {
        if (outstanding.compareTo(BigDecimal.ZERO) == 0) return ArInvoiceStatus.PAID;
        if (outstanding.compareTo(original) < 0) return ArInvoiceStatus.PARTIAL;
        return ArInvoiceStatus.OPEN;
    }

    private static ArReceiptStatus deriveReceiptStatus(BigDecimal unallocated,
                                                        BigDecimal amount,
                                                        BigDecimal allocated) {
        if (unallocated.compareTo(amount) == 0) return ArReceiptStatus.UNALLOCATED;
        if (allocated.compareTo(BigDecimal.ZERO) > 0
                && unallocated.compareTo(BigDecimal.ZERO) > 0) return ArReceiptStatus.PARTIAL;
        return ArReceiptStatus.ALLOCATED;
    }

    // -------------------------------------------------------------------------

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    static ArReceiptDto toDto(ArReceipt r, List<ArReceiptAllocation> allocs,
                               ArInvoiceRepository invoiceRepo) {
        List<AllocationDto> allocDtos = allocs.stream()
                .map(a -> {
                    String invUid = invoiceRepo.findById(a.getArInvoiceId())
                            .map(i -> i.getUid()).orElse(null);
                    return new AllocationDto(a.getId(), a.getArInvoiceId(), invUid,
                            a.getAllocatedAmount());
                })
                .toList();
        return new ArReceiptDto(
                r.getId(), r.getUid(), r.getCompanyId(), r.getBranchId(), r.getCustomerId(),
                r.getReceiptNumber(), r.getReceiptDate(), r.getAmount(), r.getUnallocatedAmount(),
                r.getCurrency(), r.getTenderType(), r.getBankReference(), r.getGlEntryUid(),
                r.getStatus(), allocDtos);
    }
}
