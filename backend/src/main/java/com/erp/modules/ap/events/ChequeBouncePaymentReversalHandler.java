package com.erp.modules.ap.events;

import com.erp.modules.ap.domain.entity.ApPayment;
import com.erp.modules.ap.domain.entity.ApPaymentAllocation;
import com.erp.modules.ap.domain.enums.SupplierBillStatus;
import com.erp.modules.ap.repository.ApPaymentAllocationRepository;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.ap.repository.SupplierBillRepository;
import com.erp.modules.cashbank.domain.dto.ChequeBouncedPayload;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code CHEQUE.BOUNCED} for OUTBOUND cheques (ADR-0041 D3). Symmetric to
 * {@link com.erp.modules.ar.events.ChequeBounceReversalHandler}: posts an APPEND-ONLY reversing
 * JournalEntry of the owning AP payment's cash leg (DR Cash|Bank / CR AP-control — the exact inverse
 * of the payment, produced by the GL engine's {@code postReversal}, so ΣDR == ΣCR by construction),
 * stamps {@code reversed_at}, and restores the relieved bill outstanding (face + base).
 *
 * <p>The bounce register transition is INBOUND-only in v1 (D-9); this handler is the ready,
 * provably-balanced consumer for the OUTBOUND path so no GL leg is ever left unbalanced when that
 * transition is added.
 */
@Component
public class ChequeBouncePaymentReversalHandler implements DomainEventHandler {

    private static final Logger log =
            LoggerFactory.getLogger(ChequeBouncePaymentReversalHandler.class);

    static final String CONSUMER = "AP.CHEQUE_BOUNCE_REVERSAL";

    private final IdempotencyGuard guard;
    private final ApPaymentRepository payments;
    private final ApPaymentAllocationRepository allocations;
    private final SupplierBillRepository bills;
    private final CompanyRepository companies;
    private final GLPostingSafeInvoker safeInvoker;
    private final ObjectMapper objectMapper;

    public ChequeBouncePaymentReversalHandler(IdempotencyGuard guard,
                                              ApPaymentRepository payments,
                                              ApPaymentAllocationRepository allocations,
                                              SupplierBillRepository bills,
                                              CompanyRepository companies,
                                              GLPostingSafeInvoker safeInvoker,
                                              ObjectMapper objectMapper) {
        this.guard        = guard;
        this.payments     = payments;
        this.allocations  = allocations;
        this.bills        = bills;
        this.companies    = companies;
        this.safeInvoker  = safeInvoker;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.CHEQUE_BOUNCED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("ChequeBouncePaymentReversalHandler: event uid={} already processed — skipping",
                    event.getUid());
            return;
        }

        ChequeBouncedPayload payload = deserialise(event.getPayload());
        Long companyId = event.getCompanyId();

        // OUTBOUND only — the AR handler owns INBOUND reversals.
        if (!"OUTBOUND".equals(payload.direction()) || payload.apPaymentUid() == null) {
            log.debug("ChequeBouncePaymentReversalHandler: cheque uid={} is not an OUTBOUND "
                    + "payment-linked bounce — skipping (AR handles INBOUND).", payload.chequeUid());
            guard.markProcessed(CONSUMER, event.getUid());
            return;
        }

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, companyId, event.getBranchId(), null));
        try {
            reversePayment(payload, companyId, event.getUid());
        } catch (Exception ex) {
            log.warn("ChequeBouncePaymentReversalHandler: reversal failed for payment uid={} "
                            + "company={} — anomaly recorded, marking processed. error={}",
                    payload.apPaymentUid(), companyId, ex.getMessage());
        } finally {
            if (previous == null) RequestContext.clear();
            else RequestContext.set(previous);
        }

        guard.markProcessed(CONSUMER, event.getUid());
    }

    // -------------------------------------------------------------------------

    private void reversePayment(ChequeBouncedPayload payload, Long companyId, String eventUid) {
        ApPayment payment = payments.findByCompanyIdAndUid(companyId, payload.apPaymentUid())
                .orElse(null);
        if (payment == null) {
            log.warn("ChequeBouncePaymentReversalHandler: payment uid={} not found in company {} — "
                    + "anomaly. event uid={}", payload.apPaymentUid(), companyId, eventUid);
            return;
        }
        if (payment.getReversedAt() != null) {
            log.debug("ChequeBouncePaymentReversalHandler: payment uid={} already reversed at {} — "
                    + "skipping", payment.getUid(), payment.getReversedAt());
            return;
        }
        if (payment.getGlEntryUid() == null) {
            log.warn("ChequeBouncePaymentReversalHandler: payment uid={} has no GL entry — cannot "
                    + "reverse. event uid={}", payment.getUid(), eventUid);
            return;
        }

        LocalDate reversalDate = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC);

        // APPEND-ONLY reversal of the payment's cash-leg journal. The engine reverses every leg of
        // the original (DR AP / CR Cash → DR Cash / CR AP), so ΣDR == ΣCR by construction.
        var reversal = safeInvoker.postReversalInNewTx(
                payment.getGlEntryUid(),
                reversalDate,
                JournalSourceType.AP_PAYMENT,
                payment.getUid(),
                null /* SYSTEM actor */);

        if (reversal == null) {
            log.warn("ChequeBouncePaymentReversalHandler: GL reversal returned null for payment "
                    + "uid={} (original entry {}) — sub-ledger left intact. event uid={}",
                    payment.getUid(), payment.getGlEntryUid(), eventUid);
            return;
        }

        // Restore the bill outstanding relieved by this payment's allocations (face + base).
        int baseScale = baseMinorUnits(companies.findById(companyId)
                .map(c -> c.getBaseCurrency()).orElse("TZS"));
        List<ApPaymentAllocation> allocs = allocations.findByApPaymentId(payment.getId());
        for (ApPaymentAllocation alloc : allocs) {
            bills.findById(alloc.getSupplierBillId()).ifPresent(bill -> {
                bill.setOutstandingAmount(bill.getOutstandingAmount().add(alloc.getAllocatedAmount()));
                if (alloc.getBaseAllocatedAmount() != null) {
                    BigDecimal billRate = bill.getFxRate() != null ? bill.getFxRate() : BigDecimal.ONE;
                    BigDecimal baseRelievedRestored = alloc.getAllocatedAmount()
                            .multiply(billRate).setScale(baseScale, RoundingMode.HALF_UP);
                    BigDecimal newBase = (bill.getBaseOutstandingAmount() != null
                            ? bill.getBaseOutstandingAmount() : BigDecimal.ZERO)
                            .add(baseRelievedRestored);
                    BigDecimal cap = bill.getBaseGrossAmount() != null
                            ? bill.getBaseGrossAmount() : bill.getGrossAmount();
                    bill.setBaseOutstandingAmount(newBase.min(cap));
                }
                bill.setStatus(bill.getOutstandingAmount().compareTo(BigDecimal.ZERO) == 0
                        ? SupplierBillStatus.PAID
                        : SupplierBillStatus.PARTIALLY_PAID);
                bill.setUpdatedAt(Instant.now());
                bills.save(bill);
            });
        }

        payment.setReversedAt(Instant.now());
        payment.setUpdatedAt(Instant.now());
        payments.save(payment);

        log.info("ChequeBouncePaymentReversalHandler: reversed payment uid={} (reversal entry {}) "
                        + "for bounced cheque uid={} company={}",
                payment.getUid(), reversal.uid(), payload.chequeUid(), companyId);
    }

    private static int baseMinorUnits(String currencyCode) {
        if (currencyCode == null) return 2;
        return switch (currencyCode) {
            case "TZS", "JPY", "KRW" -> 0;
            case "BHD", "KWD", "OMR" -> 3;
            default -> 2;
        };
    }

    private ChequeBouncedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, ChequeBouncedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise ChequeBouncedPayload: " + ex.getMessage(), ex);
        }
    }
}
