package com.erp.modules.ap.events;

import com.erp.modules.ap.service.ApPaymentReversalSupport;
import com.erp.modules.cashbank.domain.enums.CashTxnDirection;
import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.service.CashTransactionRecorder;
import com.erp.modules.ap.domain.entity.ApPayment;
import com.erp.modules.ap.repository.ApPaymentRepository;
import com.erp.modules.cashbank.domain.dto.ChequeBouncedPayload;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.platform.common.time.CompanyCalendar;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDate;
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
 * stamps {@code reversed_at}, restores the relieved bill outstanding (face + base), and zeroes the
 * payment's on-account remainder (the reversal returned it to AP-control too).
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
    private final ApPaymentReversalSupport reversalSupport;
    private final CashTransactionRecorder cashTxnRecorder;
    private final GLPostingSafeInvoker safeInvoker;
    private final ObjectMapper objectMapper;
    private final CompanyCalendar calendar;

    public ChequeBouncePaymentReversalHandler(IdempotencyGuard guard,
                                              ApPaymentRepository payments,
                                              ApPaymentReversalSupport reversalSupport,
                                              CashTransactionRecorder cashTxnRecorder,
                                              GLPostingSafeInvoker safeInvoker,
                                              ObjectMapper objectMapper,
                                              CompanyCalendar calendar) {
        this.guard        = guard;
        this.payments     = payments;
        this.reversalSupport = reversalSupport;
        this.cashTxnRecorder = cashTxnRecorder;
        this.safeInvoker  = safeInvoker;
        this.objectMapper = objectMapper;
        this.calendar     = calendar;
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
        RequestContext.set(RequestContext.Principal.system(companyId, event.getBranchId()));
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

        LocalDate reversalDate = calendar.today(companyId);

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

        // The cash book moves with the GL: the money comes back IN on the paying account.
        cashTxnRecorder.recordSettlementReversal(
                companyId, payment.getUid(), CashTxnType.AP_PAYMENT, CashTxnDirection.OUT,
                reversal.uid(), reversalDate,
                "Bounced cheque - payment " + payment.getPaymentNumber() + " reversed", null);

        // Restore the relieved bills (face + base), zero the on-account remainder and stamp
        // reversed_at — shared with the "reverse payment" command (AP-03).
        reversalSupport.restoreAndMarkReversed(payment, null /* SYSTEM actor */);

        log.info("ChequeBouncePaymentReversalHandler: reversed payment uid={} (reversal entry {}) "
                        + "for bounced cheque uid={} company={}",
                payment.getUid(), reversal.uid(), payload.chequeUid(), companyId);
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
