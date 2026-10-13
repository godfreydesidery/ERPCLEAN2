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
import com.erp.modules.gl.service.FiscalPeriodResolver;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingService;
import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.parties.domain.entity.Customer;
import com.erp.modules.parties.repository.CustomerRepository;
import com.erp.modules.tax.domain.dto.WhtCaptureResultDto;
import com.erp.modules.tax.service.WhtCaptureService;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.AccountingSetupException;
import com.erp.platform.common.api.ConflictException;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.CurrencyConversionService;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.OutboxPublisher;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Records AR receipts, allocates them (oldest-first or manual override), and posts the cash leg
 * to GL synchronously in the same transaction (ADR-0014 D-3/D-4, FR-AR-06/07, BR-AR-04/05/12).
 *
 * <p>ADR-0036 T3: when the receipt currency differs from base, a realized FX gain/loss leg is
 * injected as a base-currency balancing plug (D-5). The sacred Σbase invariant is preserved by
 * construction — all legs are posted in base currency, so {@code GLPostingServiceImpl} is
 * byte-untouched. When settlement currency == invoice currency == base the FX plug is zero and
 * OMITTED so single-currency settlements remain byte-identical (D-8).
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
    private final CurrencyConversionService fxConversion;
    private final OutboxPublisher outbox;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;
    private final ArCustomerNames customerNames;
    private final FiscalPeriodResolver fiscalPeriods;
    private final ArReceiptReversalSupport reversalSupport;
    private final CompanyCalendar calendar;

    private static final String ERR_AR_INVOICE_NOT_FOUND = "AR invoice not found.";

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
                                 CurrencyConversionService fxConversion,
                                 OutboxPublisher outbox,
                                 ScopeGuard scopeGuard,
                                 AuditService audit,
                                 ArCustomerNames customerNames,
                                 FiscalPeriodResolver fiscalPeriods,
                                 ArReceiptReversalSupport reversalSupport,
                                 CompanyCalendar calendar) {
        this.customerNames           = customerNames;
        this.fiscalPeriods           = fiscalPeriods;
        this.reversalSupport         = reversalSupport;
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
        this.fxConversion            = fxConversion;
        this.outbox                  = outbox;
        this.scopeGuard              = scopeGuard;
        this.audit                   = audit;
        this.calendar                = calendar;
    }

    @Override
    public ArReceiptDto recordAndAllocate(RecordReceiptRequest req) {
        // 1. Resolve company and scope guard
        Company company = companies.findByUid(req.companyUid())
                .orElseThrow(() -> new NotFoundException("Company not found."));
        Long companyId = company.getId();
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String baseCurrency = company.getBaseCurrency();

        // 2. Resolve customer (must belong to the company)
        Customer customer = customers.findByCompanyIdAndUid(companyId, req.customerUid())
                .orElseThrow(() -> new NotFoundException("Customer not found."));

        // ADR-0036 D-5 / D-9: read currency from req (no longer forced to base).
        // The single-currency fast path (req.currency() == base) still works byte-identically.
        String currency = (req.currency() != null && !req.currency().isBlank())
                ? req.currency()
                : baseCurrency;

        // 3. Resolve settlement rate via CurrencyConversionService (identity short-circuit when
        //    currency == baseCurrency; throws FxRateNotFoundException for unknown foreign rates).
        ConvertedAmount settlementConv = fxConversion.toBase(
                BigDecimal.ONE, currency, companyId, req.receiptDate());
        BigDecimal settlementRate = settlementConv.rate();

        // 3b. Tender must be one the ar_receipts CHECK admits (V11) — a friendly 400, not a
        //     constraint error (ARC-18: the screen used to offer "Other").
        String tenderType = normaliseTender(req.tenderType());

        // 3c. How the money is applied (ARC-20) — refused up front if contradictory.
        String allocationMode = resolveAllocationMode(req);

        // 4. Generate receipt number
        String receiptNumber = numberGen.nextReceipt(companyId);

        // 5. Create the receipt header (unallocated_amount starts == amount)
        ArReceipt receipt = new ArReceipt(
                companyId,
                RequestContext.get() != null ? RequestContext.get().branchId() : null,
                customer.getId(),
                receiptNumber,
                req.receiptDate(),
                req.amount(),
                currency,
                tenderType,
                actorId());
        // Stamp settlement rate (ADR-0036 D-4; immutable after persist)
        receipt.setFxRate(settlementRate);
        receipt.setRateAt(settlementConv.rateAt());
        // ARC-18 / LSF-18: keep the M-Pesa code / transfer ref / cheque no. the cashier typed —
        // it is how a disputed payment is traced later. Column is VARCHAR(80).
        if (req.bankReference() != null && !req.bankReference().isBlank()) {
            String ref = req.bankReference().trim();
            if (ref.length() > 80) {
                throw new IllegalArgumentException(
                        "The payment reference is too long — use at most 80 characters.");
            }
            receipt.setBankReference(ref);
        }
        // ADR-0041 D3: link the funding INBOUND cheque (lets a later bounce locate + reverse this receipt)
        if (req.chequeUid() != null && !req.chequeUid().isBlank()) {
            receipt.setChequeUid(req.chequeUid());
        }
        receipt = receipts.save(receipt);

        // 6. Build allocation set — the mode decides (ARC-20, see RecordReceiptRequest javadoc)
        List<ArReceiptAllocation> allocationList = switch (allocationMode) {
            // Oldest-first auto-allocation (BR-AR-03)
            case RecordReceiptRequest.MODE_AUTO -> autoAllocate(receipt, companyId, customer.getId());
            // Exactly the lines sent; none = the whole receipt stays on account (BR-AR-05)
            case RecordReceiptRequest.MODE_MANUAL -> hasLines(req)
                    ? manualAllocate(receipt, companyId, req.allocations())
                    : new ArrayList<>();
            // Held on account in full (BR-AR-05)
            default -> new ArrayList<>();
        };

        // 7. Apply allocation — reduce open items, capture base amounts for FX
        //    ADR-0036 D-5: accumulate Σ base_relieved (AR booked at invoice rate) and
        //    Σ base_settled (cash received at settlement rate). The difference is the FX delta.
        BigDecimal totalAllocated   = BigDecimal.ZERO;
        BigDecimal sumBaseRelieved  = BigDecimal.ZERO; // Σ(face × invoice_rate) — original AR base
        BigDecimal sumBaseSettled   = BigDecimal.ZERO; // Σ(face × settlement_rate) — cash base
        int baseScale = baseMinorUnits(baseCurrency);

        List<ArReceiptAllocation> savedAllocs = new ArrayList<>();
        for (ArReceiptAllocation alloc : allocationList) {
            ArInvoice inv = invoices.findById(alloc.getArInvoiceId())
                    .orElseThrow(() -> new NotFoundException(ERR_AR_INVOICE_NOT_FOUND));
            // Guard: allocation must not exceed current outstanding
            if (alloc.getAllocatedAmount().compareTo(inv.getOutstandingAmount()) > 0) {
                throw new IllegalStateException(
                        "Allocation " + alloc.getAllocatedAmount()
                                + " exceeds invoice outstanding of " + inv.getOutstandingAmount() + ".");
            }

            // Per-allocation base amounts (ADR-0036 D-5, allocation-junction base capture D-4)
            BigDecimal invoiceRate   = inv.getFxRate() != null ? inv.getFxRate() : BigDecimal.ONE;
            BigDecimal baseRelieved  = alloc.getAllocatedAmount()
                    .multiply(invoiceRate).setScale(baseScale, RoundingMode.HALF_UP);
            BigDecimal baseSettledSlice = alloc.getAllocatedAmount()
                    .multiply(settlementRate).setScale(baseScale, RoundingMode.HALF_UP);

            // Capture per-allocation base capture columns (V78)
            alloc.setBaseAllocatedAmount(baseSettledSlice);
            alloc.setSettlementRate(settlementRate);

            // Decrement outstanding in both face and base
            inv.setOutstandingAmount(inv.getOutstandingAmount().subtract(alloc.getAllocatedAmount()));
            // Decrement base_outstanding_amount by the base value being relieved at invoice rate
            BigDecimal currentBaseOutstanding = inv.getBaseOutstandingAmount() != null
                    ? inv.getBaseOutstandingAmount()
                    : inv.getOriginalAmount(); // fallback for pre-FX rows
            inv.setBaseOutstandingAmount(
                    currentBaseOutstanding.subtract(baseRelieved).max(BigDecimal.ZERO));
            inv.setStatus(deriveInvoiceStatus(inv.getOutstandingAmount(), inv.getOriginalAmount()));
            inv.setUpdatedAt(Instant.now());
            inv.setUpdatedBy(actorId());
            invoices.save(inv);

            savedAllocs.add(allocations.save(alloc));
            totalAllocated  = totalAllocated.add(alloc.getAllocatedAmount());
            sumBaseRelieved = sumBaseRelieved.add(baseRelieved);
            sumBaseSettled  = sumBaseSettled.add(baseSettledSlice);
        }

        // 8. Guard: total allocated must not exceed receipt amount (BR-AR-04)
        if (totalAllocated.compareTo(receipt.getAmount()) > 0) {
            throw new IllegalStateException(
                    "The total amount allocated across invoices exceeds the receipt amount."
                    + " Please reduce your allocation lines so they do not exceed the receipt total.");
        }

        // 9. Update receipt unallocated_amount and status
        BigDecimal unallocated = receipt.getAmount().subtract(totalAllocated);
        receipt.setUnallocatedAmount(unallocated);
        receipt.setStatus(deriveReceiptStatus(unallocated, receipt.getAmount(), totalAllocated));
        receipt.setUpdatedAt(Instant.now());
        receipt.setUpdatedBy(actorId());

        // 10. Post cash leg to GL synchronously (D-4). Failure rolls back the whole TX.
        //
        //    ADR-0036 D-5 — realized FX settlement (base-currency legs only):
        //      DR Cash            = Σ base_settled (unallocated portion also at settlement rate)
        //      CR AR control      = Σ base_relieved  (original base value at invoice rate)
        //      CR/DR Realized FX  = balancing plug = Σ base_relieved − Σ base_settled
        //        positive delta (base_relieved > base_settled) → customer worth less in base → FX LOSS
        //        negative delta (base_relieved < base_settled) → customer worth more  → FX GAIN
        //    When settlement currency == base (rate == 1) the plug == 0 → no FX leg emitted
        //    (single-currency path byte-identical, D-8).
        CashAccountGlResolutionDto cashRes = cashBankAccountResolver.resolve(
                companyId, req.cashBankAccountUid());
        ChartOfAccount arAcct = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_RECEIVABLE);

        boolean hasWht = req.whtTypeUid() != null
                && req.whtAmount() != null
                && req.whtAmount().compareTo(BigDecimal.ZERO) > 0;

        if (hasWht && req.whtAmount().compareTo(receipt.getAmount()) >= 0) {
            throw new IllegalArgumentException(
                    "The withholding tax must be less than the amount received.");
        }

        // Capture WHT certificate before building the draft so we have glAccountId.
        WhtCaptureResultDto whtResult = null;
        if (hasWht) {
            whtResult = whtCapture.captureOnReceipt(
                    companyId, receipt.getBranchId(),
                    req.whtTypeUid(),
                    customer.getId(), customer.getDisplayName(), customer.getTin(),
                    receipt.getUid(),
                    receipt.getAmount(), req.whtAmount(),
                    currency, receipt.getReceiptDate(),
                    null, // journal entry uid linked after post
                    actorId());
        }

        // Convert the unallocated (on-account) portion to base at settlement rate
        BigDecimal baseUnallocated = unallocated.multiply(settlementRate)
                .setScale(baseScale, RoundingMode.HALF_UP);
        // Total base cash = base of all allocated + base of unallocated
        BigDecimal totalBaseCash = sumBaseSettled.add(baseUnallocated);

        // WHT is in receipt currency; convert to base
        BigDecimal baseWht = BigDecimal.ZERO;
        if (hasWht) {
            baseWht = req.whtAmount().multiply(settlementRate)
                    .setScale(baseScale, RoundingMode.HALF_UP);
        }

        // FX leg: plug = Σ base_relieved − Σ base_settled (only on the allocated portion)
        // Positive → FX loss (we receive less base than booked); negative → FX gain
        BigDecimal fxDelta = sumBaseRelieved.subtract(sumBaseSettled);
        boolean hasFxLeg = fxDelta.compareTo(BigDecimal.ZERO) != 0;

        List<LineDraft> glLines = new ArrayList<>();

        // Cash DR = total base cash minus base WHT
        BigDecimal cashDrBase = hasWht ? totalBaseCash.subtract(baseWht) : totalBaseCash;
        glLines.add(new LineDraft(cashRes.glAccountId(), cashDrBase, BigDecimal.ZERO, baseCurrency,
                "Cash received from " + customer.getDisplayName()));

        // WHT_RECEIVABLE DR leg (base amount)
        if (hasWht) {
            glLines.add(new LineDraft(whtResult.glAccountId(), baseWht, BigDecimal.ZERO, baseCurrency,
                    "WHT receivable — " + receiptNumber));
        }

        // FX gain/loss leg — OMITTED when delta is zero (D-8: single-currency byte-identical)
        if (hasFxLeg) {
            if (fxDelta.compareTo(BigDecimal.ZERO) > 0) {
                // FX LOSS: we cleared AR at more base than we received in cash
                // DR Realized FX Loss / (the Cash DR was already the smaller number)
                ChartOfAccount fxLossAcct = glConfig.resolve(companyId, GlConfigKey.REALIZED_FX_LOSS);
                glLines.add(new LineDraft(fxLossAcct.getId(), fxDelta, BigDecimal.ZERO, baseCurrency,
                        "Realized FX loss — " + receiptNumber));
            } else {
                // FX GAIN: we received more base cash than we originally booked in AR
                // CR Realized FX Gain
                ChartOfAccount fxGainAcct = glConfig.resolve(companyId, GlConfigKey.REALIZED_FX_GAIN);
                glLines.add(new LineDraft(fxGainAcct.getId(), BigDecimal.ZERO, fxDelta.negate(), baseCurrency,
                        "Realized FX gain — " + receiptNumber));
            }
        }

        // AR CR = Σ base_relieved + base_unallocated (the base value originally debited to AR)
        // This is the balancing leg: Σ cash_DR + Σ WHT_DR + Σ FX_DR/CR = Σ AR_CR
        BigDecimal arCrBase = sumBaseRelieved.add(baseUnallocated);
        glLines.add(new LineDraft(arAcct.getId(), BigDecimal.ZERO, arCrBase, baseCurrency,
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

        // 10b. Link journal entry uid back to WHT transaction (ADR-0017 D-9).
        if (hasWht && whtResult != null) {
            whtCapture.linkJournalEntry(whtResult.whtTransactionUid(), posted.uid());
        }

        // 10c. Append cash_transaction row for this settlement (ADR-0016 D-13). The cash that
        //      actually arrives is the receipt NET of the tax the customer withheld — the same
        //      figure the GL debits to the bank above. Recording the gross made the cash book run
        //      ahead of the GL by the WHT amount.
        BigDecimal cashIn = hasWht
                ? receipt.getAmount().subtract(req.whtAmount()) : receipt.getAmount();
        cashTxnRecorder.recordSettlement(
                companyId, receipt.getBranchId(), cashRes.cashBankAccountId(),
                CashTxnType.AR_RECEIPT, CashTxnDirection.IN,
                cashIn, currency,
                receipt.getUid(), posted.uid(),
                receipt.getReceiptDate(), actorId());

        // 11. Audit
        audit.record(AuditEvent.of(AuditActions.AR_RECEIPT_RECORD, "ar_receipts",
                        receipt.getId(), receipt.getUid())
                .detail(Map.of(
                        "receiptNumber", receiptNumber,
                        "amount", receipt.getAmount().toPlainString(),
                        "customerId", String.valueOf(customer.getId()),
                        "glEntryUid", posted.uid())));

        // 12. Payment notification trigger — PAYMENT.RECEIVED (ADR-0024 D-8).
        // BR-NOTIF-13: amountFormatted must be a pre-formatted display string (e.g. "TZS 1,250.00"),
        // never a raw BigDecimal string. DecimalFormat is not thread-safe; create a new instance here.
        String amountFormatted = (currency != null ? currency + " " : "")
                + new DecimalFormat("#,##0.00").format(receipt.getAmount());
        outbox.publish(DomainEventType.PAYMENT_RECEIVED, DomainEventType.AGG_AR_RECEIPT,
                receipt.getId(), receipt.getUid(), companyId, receipt.getBranchId(),
                new PaymentReceivedPayload(receipt.getUid(), companyId, receipt.getBranchId(),
                        customer.getDisplayName(), amountFormatted,
                        currency, Instant.now()));

        return toDto(receipt, savedAllocs, invoices)
                .withCustomer(customer.getUid(), customer.getCode(), customer.getDisplayName());
    }

    @Override
    public ArReceiptDto reallocate(String receiptUid, List<AllocationLineRequest> newAllocations) {
        ArReceipt receipt = Lookups.orNotFound(receipts.findByUid(receiptUid), "ArReceipt", receiptUid);
        scopeGuard.assertCanActIn(RequestContext.get(), receipt.getCompanyId());

        // A bounced receipt brought in no money and its invoices were already restored by the
        // reversal. Re-allocating it would restore them a second time and then relieve them with
        // cash that never arrived.
        if (receipt.getReversedAt() != null) {
            throw new ConflictException(
                    "This receipt's cheque bounced and the receipt has been reversed, so it cannot be"
                    + " allocated to invoices. Record a new receipt when the customer pays.");
        }

        // FX adversarial-review MEDIUM: BASE-amount scale must come from the company BASE currency's
        // minor units, never the foreign invoice/receipt currency. Resolve it once here.
        int baseScaleForReceipt = baseMinorUnits(companies.findById(receipt.getCompanyId())
                .map(c -> c.getBaseCurrency()).orElse("TZS"));

        // Restore outstanding on currently allocated invoices
        List<ArReceiptAllocation> existing = allocations.findByReceiptId(receipt.getId());
        for (ArReceiptAllocation old : existing) {
            invoices.findById(old.getArInvoiceId()).ifPresent(inv -> {
                inv.setOutstandingAmount(inv.getOutstandingAmount().add(old.getAllocatedAmount()));
                // Restore base_outstanding_amount if base capture was recorded
                if (old.getBaseAllocatedAmount() != null) {
                    BigDecimal invoiceRate = inv.getFxRate() != null ? inv.getFxRate() : BigDecimal.ONE;
                    BigDecimal baseRelievedRestored = old.getAllocatedAmount()
                            .multiply(invoiceRate)
                            .setScale(baseScaleForReceipt, RoundingMode.HALF_UP);
                    BigDecimal newBase = (inv.getBaseOutstandingAmount() != null
                            ? inv.getBaseOutstandingAmount()
                            : BigDecimal.ZERO).add(baseRelievedRestored);
                    BigDecimal cap = inv.getBaseOriginalAmount() != null
                            ? inv.getBaseOriginalAmount() : inv.getOriginalAmount();
                    inv.setBaseOutstandingAmount(newBase.min(cap));
                }
                inv.setStatus(deriveInvoiceStatus(inv.getOutstandingAmount(), inv.getOriginalAmount()));
                inv.setUpdatedAt(Instant.now());
                inv.setUpdatedBy(actorId());
                invoices.save(inv);
            });
        }
        allocations.deleteByReceiptId(receipt.getId());
        allocations.flush();

        // Apply new allocation set
        BigDecimal settlementRate = receipt.getFxRate() != null ? receipt.getFxRate() : BigDecimal.ONE;
        int baseScale = baseScaleForReceipt;  // base-currency minor units (not the foreign receipt currency)

        BigDecimal totalAllocated = BigDecimal.ZERO;
        List<ArReceiptAllocation> saved = new ArrayList<>();
        for (AllocationLineRequest line : newAllocations) {
            ArInvoice inv = invoices.findByCompanyIdAndUid(receipt.getCompanyId(), line.arInvoiceUid())
                    .orElseThrow(() -> new NotFoundException(ERR_AR_INVOICE_NOT_FOUND));
            assertInvoiceBelongsToCustomer(inv, receipt.getCustomerId());
            if (line.allocatedAmount().compareTo(inv.getOutstandingAmount()) > 0) {
                throw new IllegalStateException(
                        "Re-allocation amount " + line.allocatedAmount()
                                + " exceeds the invoice outstanding.");
            }
            BigDecimal invoiceRate = inv.getFxRate() != null ? inv.getFxRate() : BigDecimal.ONE;
            BigDecimal baseRelieved = line.allocatedAmount()
                    .multiply(invoiceRate).setScale(baseScale, RoundingMode.HALF_UP);
            BigDecimal baseSettledSlice = line.allocatedAmount()
                    .multiply(settlementRate).setScale(baseScale, RoundingMode.HALF_UP);

            inv.setOutstandingAmount(inv.getOutstandingAmount().subtract(line.allocatedAmount()));
            BigDecimal currentBase = inv.getBaseOutstandingAmount() != null
                    ? inv.getBaseOutstandingAmount() : inv.getOriginalAmount();
            inv.setBaseOutstandingAmount(
                    currentBase.subtract(baseRelieved).max(BigDecimal.ZERO));
            inv.setStatus(deriveInvoiceStatus(inv.getOutstandingAmount(), inv.getOriginalAmount()));
            inv.setUpdatedAt(Instant.now());
            inv.setUpdatedBy(actorId());
            invoices.save(inv);

            ArReceiptAllocation alloc = new ArReceiptAllocation(
                    receipt.getCompanyId(), receipt.getId(), inv.getId(),
                    line.allocatedAmount(), line.discountAmount(), line.writeOffAmount(), actorId());
            alloc.setBaseAllocatedAmount(baseSettledSlice);
            alloc.setSettlementRate(settlementRate);
            saved.add(allocations.save(alloc));
            totalAllocated = totalAllocated.add(line.allocatedAmount());
        }
        // ARC-11: money already paid back to the customer is no longer on this receipt.
        BigDecimal available = receipt.getAmount().subtract(
                cashTxnRecorder.refundedAmount(receipt.getCompanyId(), receipt.getUid()));
        if (totalAllocated.compareTo(available) > 0) {
            throw new IllegalStateException(
                    "The total re-allocated amount exceeds what is left on the receipt."
                    + " Please reduce your allocation lines so they do not exceed the receipt total"
                    + " less anything already refunded.");
        }

        BigDecimal unallocated = available.subtract(totalAllocated);
        receipt.setUnallocatedAmount(unallocated);
        receipt.setStatus(deriveReceiptStatus(unallocated, receipt.getAmount(), totalAllocated));
        receipt.setUpdatedAt(Instant.now());
        receipt.setUpdatedBy(actorId());
        receipt = receipts.save(receipt);

        audit.record(AuditEvent.of(AuditActions.AR_RECEIPT_ALLOCATE, "ar_receipts",
                        receipt.getId(), receipt.getUid())
                .detail(Map.of("action", "reallocate")));

        return named(receipt.getCompanyId(), toDto(receipt, saved, invoices));
    }

    @Override
    public ArReceiptDto reverse(String receiptUid, String reason) {
        ArReceipt receipt = Lookups.orNotFound(receipts.findByUid(receiptUid), "ArReceipt", receiptUid);
        Long companyId = receipt.getCompanyId();
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);

        String why = reason == null ? "" : reason.trim();
        if (why.isEmpty()) {
            throw new IllegalArgumentException("Give a reason for reversing this receipt.");
        }
        if (why.length() > 200) {
            throw new IllegalArgumentException("Keep the reason to 200 characters or fewer.");
        }
        if (receipt.getReversedAt() != null) {
            throw new ConflictException("Receipt " + receipt.getReceiptNumber()
                    + " has already been reversed.");
        }
        if (receipt.getGlEntryUid() == null) {
            throw new ConflictException("Receipt " + receipt.getReceiptNumber()
                    + " has no ledger entry, so it cannot be reversed here."
                    + " Ask your accountant to correct it with a journal.");
        }

        // ARC-11: part of this receipt was paid back to the customer. Reversing it would put the
        // whole receipt back on the customer's account, refund included.
        if (cashTxnRecorder.refundedAmount(companyId, receipt.getUid()).signum() > 0) {
            throw new ConflictException("Part of receipt " + receipt.getReceiptNumber()
                    + " was refunded to the customer, so it cannot be reversed here."
                    + " Ask your accountant to correct it with a journal.");
        }

        // The receipt's own period must still be open: undoing money in a period that has been
        // closed (and possibly reported) is an accountant's decision, not a cashier correction.
        try {
            fiscalPeriods.resolveOpen(companyId, receipt.getReceiptDate());
        } catch (AccountingSetupException closed) {
            throw new ConflictException("Receipt " + receipt.getReceiptNumber() + " is dated "
                    + receipt.getReceiptDate().format(
                            DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH))
                    + ", in an accounting period that is closed, so it cannot be reversed."
                    + " Ask your accountant to reopen the period or post a correcting journal.");
        }

        // Withholding tax: the receipt carries a WHT certificate the reversal cannot cancel, so
        // reversing it here would leave the certificate claiming tax that was never withheld.
        // The cash book holds the NET cash, so a shortfall against the receipt amount is the WHT.
        Optional<BigDecimal> cashIn = cashTxnRecorder.settledAmount(
                companyId, receipt.getUid(), CashTxnType.AR_RECEIPT, CashTxnDirection.IN);
        if (cashIn.isPresent() && cashIn.get().compareTo(receipt.getAmount()) < 0) {
            throw new ConflictException("Receipt " + receipt.getReceiptNumber()
                    + " had withholding tax deducted, so it cannot be reversed here."
                    + " Ask your accountant to correct it with a journal.");
        }

        // Dated today (never before the receipt itself), like a bounced-cheque reversal.
        LocalDate today = calendar.today(companyId);
        LocalDate reversalDate = today.isBefore(receipt.getReceiptDate())
                ? receipt.getReceiptDate() : today;

        // APPEND-ONLY reversal of the receipt's journal: every leg swapped (DR AR / CR Cash, and
        // the FX leg if any), so debits equal credits by construction. Same TX: a GL refusal rolls
        // back the whole command and leaves the sub-ledger untouched.
        JournalEntryDto reversal = glPosting.postReversal(
                receipt.getGlEntryUid(), reversalDate, JournalSourceType.AR_RECEIPT,
                receipt.getUid(), actorId(),
                "receipt " + receipt.getReceiptNumber() + " reversed: " + why);

        // The cash book moves with the GL: the opposite row on the same cash/bank account.
        cashTxnRecorder.recordSettlementReversal(
                companyId, receipt.getUid(), CashTxnType.AR_RECEIPT, CashTxnDirection.IN,
                reversal.uid(), reversalDate,
                "Reversal of receipt " + receipt.getReceiptNumber() + " - " + why, actorId());

        receipt = reversalSupport.restoreAndMarkReversed(receipt, actorId());

        audit.record(AuditEvent.of(AuditActions.AR_RECEIPT_REVERSE, "ar_receipts",
                        receipt.getId(), receipt.getUid())
                .detail(Map.of(
                        "receiptNumber", receipt.getReceiptNumber(),
                        "amount", receipt.getAmount().toPlainString(),
                        "reversalEntryUid", reversal.uid(),
                        "reason", why)));

        List<ArReceiptAllocation> allocs = allocations.findByReceiptId(receipt.getId());
        return named(companyId, toDto(receipt, allocs, invoices));
    }

    @Override
    @Transactional(readOnly = true)
    public ArReceiptDto getByUid(String uid) {
        ArReceipt receipt = Lookups.orNotFound(receipts.findByUid(uid), "ArReceipt", uid);
        scopeGuard.assertCanActIn(RequestContext.get(), receipt.getCompanyId());
        List<ArReceiptAllocation> allocs = allocations.findByReceiptId(receipt.getId());
        return named(receipt.getCompanyId(), toDto(receipt, allocs, invoices));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ArReceiptDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return named(companyId, receipts.findByCompanyId(companyId, pageable)
                .map(r -> toDto(r, allocations.findByReceiptId(r.getId()), invoices)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ArReceiptDto> listByCustomer(Long companyId, Long customerId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return named(companyId, receipts.findByCompanyIdAndCustomerId(companyId, customerId, pageable)
                .map(r -> toDto(r, allocations.findByReceiptId(r.getId()), invoices)));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ArReceiptDto> list(Long companyId, Long customerId, String customerUid,
                                   Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        Long custId = (customerUid != null && !customerUid.isBlank())
                ? customerNames.idOf(companyId, customerUid)
                : customerId;
        return custId != null
                ? listByCustomer(companyId, custId, pageable)
                : listByCompany(companyId, pageable);
    }

    /** One receipt carrying its customer's uid, code and name. */
    private ArReceiptDto named(Long companyId, ArReceiptDto dto) {
        return customerNames.fillReceipts(companyId, List.of(dto)).get(0);
    }

    /** The page with each receipt's customer named. */
    private Page<ArReceiptDto> named(Long companyId, Page<ArReceiptDto> page) {
        return new PageImpl<>(customerNames.fillReceipts(companyId, page.getContent()),
                page.getPageable(), page.getTotalElements());
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

    private static boolean hasLines(RecordReceiptRequest req) {
        return req.allocations() != null && !req.allocations().isEmpty();
    }

    /**
     * ARC-20: the allocation mode the receipt is recorded under. Absent keeps the historical rule
     * (lines = MANUAL, no lines = AUTO) so callers that send nothing behave exactly as before; an
     * explicit AUTO or ON_ACCOUNT with lines is contradictory and refused rather than guessed.
     */
    static String resolveAllocationMode(RecordReceiptRequest req) {
        String raw = req.allocationMode();
        if (raw == null || raw.isBlank()) {
            return hasLines(req) ? RecordReceiptRequest.MODE_MANUAL : RecordReceiptRequest.MODE_AUTO;
        }
        String mode = raw.trim().toUpperCase(java.util.Locale.ROOT);
        switch (mode) {
            case RecordReceiptRequest.MODE_MANUAL:
                return mode;
            case RecordReceiptRequest.MODE_AUTO:
            case RecordReceiptRequest.MODE_ON_ACCOUNT:
                if (hasLines(req)) {
                    throw new IllegalArgumentException(
                            "Allocation lines can only be sent when the allocation is chosen by hand."
                            + " Remove the lines, or choose to allocate manually.");
                }
                return mode;
            default:
                throw new IllegalArgumentException(
                        "Choose how to apply the receipt: automatically to the oldest invoices,"
                        + " to the invoices you pick, or keep it all on account.");
        }
    }

    private List<ArReceiptAllocation> manualAllocate(ArReceipt receipt,
                                                      Long companyId,
                                                      List<AllocationLineRequest> lines) {
        List<ArReceiptAllocation> result = new ArrayList<>();
        for (AllocationLineRequest line : lines) {
            ArInvoice inv = invoices.findByCompanyIdAndUid(companyId, line.arInvoiceUid())
                    .orElseThrow(() -> new NotFoundException(
                            ERR_AR_INVOICE_NOT_FOUND));
            assertInvoiceBelongsToCustomer(inv, receipt.getCustomerId());
            result.add(new ArReceiptAllocation(
                    companyId, receipt.getId(), inv.getId(),
                    line.allocatedAmount(), line.discountAmount(), line.writeOffAmount(), actorId()));
        }
        return result;
    }

    /**
     * BR-AR customer-match guard (issue #1): an allocation may only target an invoice that belongs
     * to the same customer as the receipt/credit-note. Throws {@link ConflictException} (→ HTTP 409)
     * when the invariant is violated.
     */
    private static void assertInvoiceBelongsToCustomer(ArInvoice invoice, Long expectedCustomerId) {
        if (!invoice.getCustomerId().equals(expectedCustomerId)) {
            // BR-AR customer-match: allocation may only target an invoice belonging to the same customer as the receipt
            throw new ConflictException(
                    "The selected invoice does not belong to this customer."
                    + " Please choose an invoice raised for the same customer as this receipt.");
        }
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

    /** The tender values the ar_receipts CHECK admits (V11 chk_ar_receipt_tender). */
    static final java.util.Set<String> ALLOWED_TENDERS =
            java.util.Set.of("CASH", "BANK_TRANSFER", "MOBILE_MONEY", "CHEQUE", "CARD");

    /** Upper-cased, trimmed tender; anything the CHECK would reject is a friendly 400. */
    static String normaliseTender(String tender) {
        String t = tender == null ? "" : tender.trim().toUpperCase(java.util.Locale.ROOT);
        if (!ALLOWED_TENDERS.contains(t)) {
            throw new IllegalArgumentException(
                    "Choose how the customer paid: Cash, Bank transfer, Mobile money, Cheque or Card.");
        }
        return t;
    }

    /**
     * Minor-unit scale for rounding. TZS=0, USD/EUR/KES/GBP=2.
     * Defaults to 2 when the currency is unrecognised (safe fallback).
     */
    private static int baseMinorUnits(String currencyCode) {
        if (currencyCode == null) return 2;
        return switch (currencyCode) {
            case "TZS", "JPY", "KRW" -> 0;
            case "BHD", "KWD", "OMR" -> 3;
            default -> 2;
        };
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
                r.getCurrency().value(), r.getTenderType(), r.getBankReference(), r.getGlEntryUid(),
                r.getStatus(), allocDtos).withReversedAt(r.getReversedAt());
    }
}
