package com.erp.modules.cashbank.events;

import com.erp.modules.cashbank.domain.enums.CashTxnType;
import com.erp.modules.cashbank.service.CashBookJournalMirror;
import com.erp.modules.sales.domain.dto.PosCashMovedPayload;
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
 * POS till cash reaches the cash book (gap review wave 3, ARC-08). Consumes
 * {@code POS.CASH.MOVED} — published by the POS session service in the same transaction as the
 * payout / expense or over/short journal — and writes {@code POS_PAYOUT} / {@code POS_VARIANCE}
 * rows mirroring that journal's cash leg. The rows land on the cash/bank account linked to the GL
 * account the journal credited or debited (the company {@code CASH} mapping today), which is the
 * till's drawer account whenever the till is linked to that GL account. No GL posting here.
 */
@Component
public class PosCashBookHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(PosCashBookHandler.class);

    static final String CONSUMER = "CASHBANK.POS_CASH";

    private final IdempotencyGuard guard;
    private final CashBookJournalMirror mirror;
    private final ObjectMapper objectMapper;

    public PosCashBookHandler(IdempotencyGuard guard, CashBookJournalMirror mirror,
                              ObjectMapper objectMapper) {
        this.guard = guard;
        this.mirror = mirror;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.POS_CASH_MOVED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            return;
        }
        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(event.getCompanyId(), event.getBranchId()));
        try {
            PosCashMovedPayload p = objectMapper.readValue(event.getPayload(),
                    PosCashMovedPayload.class);
            CashTxnType type = PosCashMovedPayload.KIND_VARIANCE.equals(p.kind())
                    ? CashTxnType.POS_VARIANCE : CashTxnType.POS_PAYOUT;
            mirror.mirrorPosCash(event.getCompanyId(), event.getBranchId(), type,
                    p.sourceUid(), p.journalEntryUid(), p.memo());
        } catch (Exception ex) {
            log.warn("PosCashBookHandler: cash-book row for event uid={} company={} not written "
                    + "— error={}", event.getUid(), event.getCompanyId(), ex.getMessage());
        } finally {
            SaleCashBookHandler.restore(previous);
        }
        guard.markProcessed(CONSUMER, event.getUid());
    }
}
