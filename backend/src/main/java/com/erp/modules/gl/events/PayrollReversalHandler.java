package com.erp.modules.gl.events;

import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.hr.domain.event.PayrollReversedPayload;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code PAYROLL.REVERSED} — posts a GL reversal for the original payroll journal
 * (ADR-0032 D-9). Mirrors {@link PayrollPostingHandler} idempotency pattern.
 */
@Component
public class PayrollReversalHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PayrollReversalHandler.class);

    static final String CONSUMER = "GL.PAYROLL_REVERSAL";

    private final IdempotencyGuard     guard;
    private final GLPostingSafeInvoker safeInvoker;
    private final ObjectMapper         objectMapper;

    public PayrollReversalHandler(IdempotencyGuard guard,
                                   GLPostingSafeInvoker safeInvoker,
                                   ObjectMapper objectMapper) {
        this.guard        = guard;
        this.safeInvoker  = safeInvoker;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.PAYROLL_REVERSED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("PayrollReversalHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        PayrollReversedPayload payload = deserialise(event.getPayload());

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            if (payload.originalGlEntryUid() != null) {
                safeInvoker.postReversalInNewTx(
                        payload.originalGlEntryUid(),
                        payload.payDate(),
                        JournalSourceType.PAYROLL,
                        "REVERSAL:" + payload.runNumber(),
                        null);
                log.info("PayrollReversalHandler: GL reversal posted for payroll run {} company={}.",
                        payload.runNumber(), event.getCompanyId());
            } else {
                log.warn("PayrollReversalHandler: no original GL entry uid for run {} — "
                        + "no reversal posted. event uid={}", payload.runNumber(), event.getUid());
            }
        } catch (Exception ex) {
            log.warn("PayrollReversalHandler: GL reversal failed for run uid={} company={} — "
                            + "error={} event uid={}",
                    payload.runUid(), event.getCompanyId(), ex.getMessage(), event.getUid());
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }

        guard.markProcessed(CONSUMER, event.getUid());
    }

    private PayrollReversedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, PayrollReversedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise PayrollReversedPayload: " + ex.getMessage(), ex);
        }
    }
}
