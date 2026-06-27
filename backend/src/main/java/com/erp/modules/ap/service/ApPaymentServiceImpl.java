package com.erp.modules.ap.service;

import com.erp.modules.ap.domain.dto.ApPaymentDto;
import com.erp.modules.ap.domain.dto.ApPaymentDto.PaymentAllocationDto;
import com.erp.modules.ap.domain.dto.PaySingleBillRequest;
import com.erp.modules.ap.domain.dto.PaymentRunRequest;
import com.erp.modules.ap.domain.entity.ApPayment;
import com.erp.modules.ap.domain.entity.ApPaymentAllocation;
import com.erp.modules.ap.domain.entity.PaymentRun;
import com.erp.modules.ap.domain.entity.SupplierBill;
import com.erp.modules.ap.domain.enums.ApPaymentKind;
import com.erp.modules.ap.domain.enums.ApPaymentStatus;
import com.erp.modules.ap.domain.enums.PaymentRunStatus;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.ApPaymentAllocationRepository;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.PaymentRunRepository;
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
import com.erp.platform.common.money.ConvertedAmount;
import com.erp.platform.common.money.CurrencyConversionService;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 *
 * <p>ADR-0036 T3: when the payment currency differs from base, a realized FX gain/loss leg is
 * injected as a base-currency balancing plug (D-5). All GL legs are in base currency so the
 * GL engine ({@code GLPostingServiceImpl}) is byte-untouched. When settlement currency == base the
 * FX plug is zero and OMITTED (single-currency path byte-identical, D-8).
 *
 * <p>AP FX math (mirror of AR, signs inverted):
 * <ul>
 *   <li>DR AP control = Σ base_relieved (original bill rate — the base we originally booked)</li>
 *   <li>CR Cash       = Σ base_settled  (settlement rate)</li>
 *   <li>FX delta = base_settled − base_relieved</li>
 *   <li>positive delta (we pay MORE base than booked) → FX LOSS → DR REALIZED_FX_LOSS</li>
 *   <li>negative delta (we pay LESS base than booked)  → FX GAIN → CR REALIZED_FX_GAIN</li>
 * </ul>
 */
@Service
@Transactional
public class ApPaymentServiceImpl implements ApPaymentService {

    private final SupplierBillRepository        bills;
    private final ApPaymentRepository           payments;
    private final ApPaymentAllocationRepository allocations;
    private final PaymentRunRepository          paymentRuns;
    private final CompanyRepository             companies;
    private final SupplierRepository            suppliers;
    private final ApBillNumberGenerator         numbers;
    private final GLPostingService              glPosting;
    private final GLConfigResolver              glConfig;
    private final CashBankAccountResolver       cashBankAccountResolver;
    private final CashTransactionRecorder       cashTxnRecorder;
    private final WhtCaptureService             whtCapture;
    private final CurrencyConversionService     fxConversion;
    private final ScopeGuard                    scopeGuard;
    private final AuditService                  audit;

    public ApPaymentServiceImpl(SupplierBillRepository bills,
                                 ApPaymentRepository payments,
                                 ApPaymentAllocationRepository allocations,
                                 PaymentRunRepository paymentRuns,
                                 CompanyRepository companies,
                                 SupplierRepository suppliers,
                                 ApBillNumberGenerator numbers,
                                 GLPostingService glPosting,
                                 GLConfigResolver glConfig,
                                 CashBankAccountResolver cashBankAccountResolver,
                                 CashTransactionRecorder cashTxnRecorder,
                                 WhtCaptureService whtCapture,
                                 CurrencyConversionService fxConversion,
                                 ScopeGuard scopeGuard,
                                 AuditService audit) {
        this.bills                   = bills;
        this.payments                = payments;
        this.allocations             = allocations;
        this.paymentRuns             = paymentRuns;
        this.companies               = companies;
        this.suppliers               = suppliers;
        this.numbers                 = numbers;
        this.glPosting               = glPosting;
        this.glConfig                = glConfig;
        this.cashBankAccountResolver = cashBankAccountResolver;
        this.cashTxnRecorder         = cashTxnRecorder;
        this.whtCapture              = whtCapture;
        this.fxConversion            = fxConversion;
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
            throw new IllegalStateException("This bill has zero outstanding amount.");
        }

        String currency = bill.getCurrency().value();
        String payNum   = numbers.nextPayment(companyId);

        // Resolve settlement rate (identity short-circuit when currency == base)
        ConvertedAmount settlementConv = fxConversion.toBase(
                BigDecimal.ONE, currency, companyId, req.paymentDate());
        BigDecimal settlementRate = settlementConv.rate();
        int baseScale = baseMinorUnits(currency);

        ApPayment payment = new ApPayment(
                companyId, branchId(), bill.getSupplierId(),
                payNum, ApPaymentKind.SINGLE,
                req.paymentDate(), toAllocate, currency,
                req.tenderType(), req.bankReference(), actorId());
        // Stamp settlement rate on payment header (ADR-0036 D-4)
        payment.setFxRate(settlementRate);
        payment.setRateAt(settlementConv.rateAt());
        payment = payments.save(payment);

        // Compute per-allocation base amounts
        BigDecimal billRate = bill.getFxRate() != null ? bill.getFxRate() : BigDecimal.ONE;
        BigDecimal baseRelieved  = toAllocate.multiply(billRate)
                .setScale(baseScale, RoundingMode.HALF_UP);
        BigDecimal baseSettled   = toAllocate.multiply(settlementRate)
                .setScale(baseScale, RoundingMode.HALF_UP);

        // Allocate to bill — capture base columns (V78) + settlement discount / write-off (D1, data-only)
        ApPaymentAllocation alloc = new ApPaymentAllocation(
                companyId, payment.getId(), bill.getId(), toAllocate,
                req.discountAmount(), req.writeOffAmount(), actorId());
        alloc.setBaseAllocatedAmount(baseSettled);
        alloc.setSettlementRate(settlementRate);
        allocations.save(alloc);

        // Update bill outstanding + base_outstanding_amount + status
        bill.setOutstandingAmount(bill.getOutstandingAmount().subtract(toAllocate));
        BigDecimal currentBase = bill.getBaseOutstandingAmount() != null
                ? bill.getBaseOutstandingAmount() : bill.getGrossAmount();
        bill.setBaseOutstandingAmount(currentBase.subtract(baseRelieved).max(BigDecimal.ZERO));
        bill.setStatus(billStatusAfterPayment(bill.getOutstandingAmount()));
        bills.save(bill);

        // D-9: set on-account tracking — for a normal single-bill payment, toAllocate == amount
        // so unallocated == 0 and status == ALLOCATED. For a partial payment the remainder stays.
        BigDecimal unallocated = payment.getAmount().subtract(toAllocate);
        payment.setUnallocatedAmount(unallocated);
        payment.setStatus(derivePaymentStatus(unallocated, payment.getAmount(), toAllocate));

        // GL: DR AP / CR Cash (+FX leg if applicable)
        JournalEntryDto posted = postPaymentToGl(
                payment, companyId, bill.getBranchId(), currency,
                req.cashBankAccountUid(), bill.getSupplierId(),
                req.whtTypeUid(), req.whtAmount(),
                baseRelieved, baseSettled);
        payment.setGlEntryUid(posted.uid());
        payment = payments.save(payment);

        audit.record(AuditEvent.of(AuditActions.AP_PAYMENT_MAKE, "ap_payments",
                        payment.getId(), payment.getUid())
                .detail(Map.of("paymentNumber", payNum,
                        "amount", toAllocate.toPlainString(),
                        "billUid", req.supplierBillUid())));

        List<ApPaymentAllocation> allocList = allocations.findByApPaymentId(payment.getId());
        return toDto(payment, allocList, bills);
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
                    .orElseThrow(() -> new NotFoundException("Supplier not found."));
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

        // FX adversarial-review HIGH (ADR-0036 D — same-currency guard): a payment run applies ONE
        // settlement rate to every bill (the ApPayment header is single-currency). Mixing currencies
        // would corrupt base-cash + realized FX on every bill but the first. Reject heterogeneous
        // runs early — the caller must split them by currency.
        long distinctCurrencies = openBills.stream()
                .map(SupplierBill::getCurrency)
                .distinct()
                .count();
        if (distinctCurrencies > 1) {
            throw new IllegalStateException(
                    "Payment run bills must share one currency — found " + distinctCurrencies
                    + " distinct currencies. Split the run by currency.");
        }

        String currency = openBills.get(0).getCurrency().value();
        // Resolve settlement rate once for the run
        ConvertedAmount settlementConv = fxConversion.toBase(
                BigDecimal.ONE, currency, companyId, req.paymentDate());
        BigDecimal settlementRate = settlementConv.rate();
        int baseScale = baseMinorUnits(currency);

        // Compute total first so the INSERT never violates chk_ap_payment_amount (amount > 0)
        BigDecimal totalPaid = openBills.stream()
                .map(SupplierBill::getOutstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        String payNum = numbers.nextPayment(companyId);

        // ADR-0041 D3 — create the grouping PaymentRun (DRAFT) before the member payment. The run
        // posts NOTHING to GL (balance-neutral); its member payment does. Stamped POSTED below once
        // the member payment's GL entry succeeds. run_number via the dedicated run sequence.
        PaymentRun run = new PaymentRun(
                companyId, branchId(), numbers.nextPaymentRun(companyId), currency, actorId());
        run.setTotalAmount(totalPaid);
        run = paymentRuns.save(run);

        final Long finalSupplierId = supplierId;
        ApPayment payment = new ApPayment(
                companyId, branchId(), finalSupplierId,
                payNum, ApPaymentKind.PAYMENT_RUN,
                req.paymentDate(), totalPaid, currency,
                req.tenderType(), req.bankReference(), actorId());
        payment.setFxRate(settlementRate);
        payment.setRateAt(settlementConv.rateAt());
        // ADR-0041 D3 — group this payment under the run (soft-FK).
        payment.setPaymentRunId(run.getId());
        payment = payments.save(payment);

        List<ApPaymentAllocation> allocList = new ArrayList<>();
        BigDecimal sumBaseRelieved = BigDecimal.ZERO;
        BigDecimal sumBaseSettled  = BigDecimal.ZERO;

        for (SupplierBill bill : openBills) {
            BigDecimal toAllocate = bill.getOutstandingAmount();
            BigDecimal billRate   = bill.getFxRate() != null ? bill.getFxRate() : BigDecimal.ONE;
            BigDecimal baseRelieved = toAllocate.multiply(billRate)
                    .setScale(baseScale, RoundingMode.HALF_UP);
            BigDecimal baseSettled  = toAllocate.multiply(settlementRate)
                    .setScale(baseScale, RoundingMode.HALF_UP);

            ApPaymentAllocation alloc = new ApPaymentAllocation(
                    companyId, payment.getId(), bill.getId(), toAllocate, actorId());
            alloc.setBaseAllocatedAmount(baseSettled);
            alloc.setSettlementRate(settlementRate);
            allocList.add(allocations.save(alloc));

            bill.setOutstandingAmount(BigDecimal.ZERO);
            bill.setBaseOutstandingAmount(BigDecimal.ZERO);
            bill.setStatus(SupplierBillStatus.PAID);
            bills.save(bill);

            sumBaseRelieved = sumBaseRelieved.add(baseRelieved);
            sumBaseSettled  = sumBaseSettled.add(baseSettled);
        }

        // D-9: payment run always fully allocates — unallocated == 0, status == ALLOCATED
        payment.setUnallocatedAmount(BigDecimal.ZERO);
        payment.setStatus(ApPaymentStatus.ALLOCATED);

        // For a payment run the WHT applies to the batch total; supplier id from first bill if batch.
        Long runSupplierId = openBills.isEmpty() ? null : openBills.get(0).getSupplierId();
        JournalEntryDto posted = postPaymentToGl(
                payment, companyId, branchId(), currency,
                req.cashBankAccountUid(), runSupplierId,
                req.whtTypeUid(), req.whtAmount(),
                sumBaseRelieved, sumBaseSettled);
        payment.setGlEntryUid(posted.uid());
        payment = payments.save(payment);

        // ADR-0041 D3 — the member payment posted; flip the run DRAFT→POSTED (balance-neutral).
        run.setStatus(PaymentRunStatus.POSTED);
        run.setUpdatedAt(java.time.Instant.now());
        run.setUpdatedBy(actorId());
        paymentRuns.save(run);

        audit.record(AuditEvent.of(AuditActions.AP_PAYMENT_MAKE, "ap_payments",
                        payment.getId(), payment.getUid())
                .detail(Map.of("paymentNumber", payNum,
                        "runNumber", run.getRunNumber(),
                        "totalPaid", totalPaid.toPlainString(),
                        "billCount", String.valueOf(openBills.size()))));

        return toDto(payment, allocList, bills);
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
        return toDto(payment, allocList, bills);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApPaymentDto> listByCompany(Long companyId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return payments.findByCompanyId(companyId, pageable)
                .map(p -> toDto(p, allocations.findByApPaymentId(p.getId()), bills));
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ApPaymentDto> listBySupplier(Long companyId, Long supplierId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return payments.findByCompanyIdAndSupplierId(companyId, supplierId, pageable)
                .map(p -> toDto(p, allocations.findByApPaymentId(p.getId()), bills));
    }

    // -------------------------------------------------------------------------
    // GL posting
    // -------------------------------------------------------------------------

    /**
     * Posts the AP payment journal in base currency.
     *
     * <p>ADR-0036 D-5 math (AP mirror of AR):
     * <ul>
     *   <li>DR AP control  = sumBaseRelieved (original bill rate)</li>
     *   <li>CR Cash        = sumBaseSettled  (settlement rate)</li>
     *   <li>fxDelta = sumBaseSettled − sumBaseRelieved</li>
     *   <li>positive (pay MORE base than booked) → FX LOSS → DR REALIZED_FX_LOSS</li>
     *   <li>negative (pay LESS base than booked) → FX GAIN → CR REALIZED_FX_GAIN</li>
     * </ul>
     *
     * <p>When rate==1 for all bills, fxDelta==0 and no FX leg is emitted — single-currency
     * path byte-identical (D-8).
     *
     * @param sumBaseRelieved Σ(allocated_face × bill_fx_rate)   — base value originally booked
     * @param sumBaseSettled  Σ(allocated_face × settlement_rate) — base value of cash paid
     */
    private JournalEntryDto postPaymentToGl(ApPayment payment, Long companyId,
                                             Long branchId, String currency,
                                             String cashBankAccountUid,
                                             Long supplierId,
                                             String whtTypeUid, BigDecimal whtAmount,
                                             BigDecimal sumBaseRelieved,
                                             BigDecimal sumBaseSettled) {
        String baseCurrency = companies.findById(companyId)
                .map(c -> c.getBaseCurrency())
                .orElseThrow(() -> new IllegalStateException("Company not found."));

        ChartOfAccount apAcct = glConfig.resolve(companyId, GlConfigKey.ACCOUNTS_PAYABLE);

        // ADR-0016 D-8: resolve cash/bank account via CashBankAccountResolver (replaces glConfig.CASH)
        CashAccountGlResolutionDto cashRes = cashBankAccountResolver.resolve(companyId, cashBankAccountUid);

        boolean hasWht = whtTypeUid != null
                && whtAmount != null
                && whtAmount.compareTo(BigDecimal.ZERO) > 0;

        // Capture WHT certificate before building the GL draft (ADR-0017 D-9).
        // D-7: resolve supplier TIN snapshot from party master (no tax→parties import — done here in ap).
        WhtCaptureResultDto whtResult = null;
        if (hasWht) {
            String supplierTin = supplierId != null
                    ? suppliers.findById(supplierId).map(s -> s.getTin()).orElse(null)
                    : null;
            whtResult = whtCapture.captureOnPayment(
                    companyId, branchId,
                    whtTypeUid,
                    supplierId, "Supplier",
                    supplierTin,
                    payment.getUid(),
                    payment.getAmount(), whtAmount,
                    currency, payment.getPaymentDate(),
                    null,
                    actorId());
        }

        int baseScale = baseMinorUnits(baseCurrency);
        BigDecimal settlementRate = payment.getFxRate() != null ? payment.getFxRate() : BigDecimal.ONE;

        // Convert WHT to base
        BigDecimal baseWht = BigDecimal.ZERO;
        if (hasWht) {
            baseWht = whtAmount.multiply(settlementRate)
                    .setScale(baseScale, RoundingMode.HALF_UP);
        }

        // FX delta: how much MORE base we paid vs. what we booked
        // positive → FX loss (we paid out more base than we originally recorded as payable)
        // negative → FX gain (we paid out less base)
        BigDecimal fxDelta   = sumBaseSettled.subtract(sumBaseRelieved);
        boolean    hasFxLeg  = fxDelta.compareTo(BigDecimal.ZERO) != 0;

        List<LineDraft> glLines = new ArrayList<>();

        // DR AP control = base value originally booked (invoice rate)
        glLines.add(new LineDraft(apAcct.getId(),
                sumBaseRelieved, BigDecimal.ZERO,
                baseCurrency, "AP payment — " + payment.getPaymentNumber()));

        // FX leg — OMITTED when delta is zero (D-8: single-currency byte-identical)
        if (hasFxLeg) {
            if (fxDelta.compareTo(BigDecimal.ZERO) > 0) {
                // FX LOSS: we paid out more base than we booked
                ChartOfAccount fxLossAcct = glConfig.resolve(companyId, GlConfigKey.REALIZED_FX_LOSS);
                glLines.add(new LineDraft(fxLossAcct.getId(),
                        fxDelta, BigDecimal.ZERO,
                        baseCurrency, "Realized FX loss — " + payment.getPaymentNumber()));
            } else {
                // FX GAIN: we paid out less base than we booked
                ChartOfAccount fxGainAcct = glConfig.resolve(companyId, GlConfigKey.REALIZED_FX_GAIN);
                glLines.add(new LineDraft(fxGainAcct.getId(),
                        BigDecimal.ZERO, fxDelta.negate(),
                        baseCurrency, "Realized FX gain — " + payment.getPaymentNumber()));
            }
        }

        // CR Cash = base settled minus base WHT
        BigDecimal cashCrBase = hasWht ? sumBaseSettled.subtract(baseWht) : sumBaseSettled;
        glLines.add(new LineDraft(cashRes.glAccountId(),
                BigDecimal.ZERO, cashCrBase,
                baseCurrency, "Cash out — " + payment.getPaymentNumber()));

        if (hasWht) {
            glLines.add(new LineDraft(whtResult.glAccountId(),
                    BigDecimal.ZERO, baseWht,
                    baseCurrency, "WHT payable — " + payment.getPaymentNumber()));
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

    private Long resolveCompany(String uid) {
        return companies.findByUid(uid)
                .map(c -> c.getId())
                .orElseThrow(() -> new NotFoundException("Company not found."));
    }

    private Long branchId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.branchId() : null;
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }

    static ApPaymentDto toDto(ApPayment p, List<ApPaymentAllocation> allocList,
                               SupplierBillRepository billRepo) {
        List<PaymentAllocationDto> dtoAllocs = allocList.stream()
                .map(a -> {
                    String billUid = billRepo.findById(a.getSupplierBillId())
                            .map(b -> b.getUid()).orElse(null);
                    return new PaymentAllocationDto(
                            a.getId(), a.getSupplierBillId(), billUid, a.getAllocatedAmount());
                })
                .toList();
        return new ApPaymentDto(
                p.getId(), p.getUid(), p.getCompanyId(), p.getBranchId(), p.getSupplierId(),
                p.getPaymentNumber(), p.getKind(), p.getPaymentDate(), p.getAmount(),
                p.getCurrency().value(), p.getTenderType(), p.getBankReference(), p.getGlEntryUid(),
                p.getWhtAmount(), p.getWhtTransactionUid(),
                p.getUnallocatedAmount(), p.getStatus(), p.getChequeUid(),
                p.getPaymentRunId(),
                dtoAllocs);
    }

    // -------------------------------------------------------------------------
    // D-9 — status derivation helper (mirror of ArReceiptServiceImpl)
    // -------------------------------------------------------------------------

    static ApPaymentStatus derivePaymentStatus(BigDecimal unallocated, BigDecimal amount,
                                                BigDecimal allocated) {
        if (unallocated.compareTo(amount) == 0) return ApPaymentStatus.UNALLOCATED;
        if (allocated.compareTo(BigDecimal.ZERO) > 0
                && unallocated.compareTo(BigDecimal.ZERO) > 0) return ApPaymentStatus.PARTIAL;
        return ApPaymentStatus.ALLOCATED;
    }
}
