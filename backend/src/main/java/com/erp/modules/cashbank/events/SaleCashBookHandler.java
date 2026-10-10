package com.erp.modules.cashbank.events;

import com.erp.modules.cashbank.service.CashBookJournalMirror;
import com.erp.modules.sales.domain.dto.SaleFinalisedPayload;
import com.erp.modules.sales.domain.dto.SaleVoidedPayload;
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
 * Sales reach the cash book (gap review wave 3, ARC-08). Consumes {@code SALE.FINALISED} and
 * {@code SALE.VOIDED} and writes the cash side of the journal the GL handlers posted for them —
 * {@code SALE_TENDER} rows for a sale, {@code SALE_REFUND} rows for its void. No GL posting here.
 *
 * <p>Runs after the GL handlers of the same event ({@code SalesPostingHandler} /
 * {@code SaleVoidingHandler} carry a higher {@code @Order}), whose journal is committed in its own
 * transaction before this handler reads it. Idempotent twice over: the {@link IdempotencyGuard}
 * marker, and the mirror skipping a sale that already has rows of that type. A mirror failure is
 * logged and never rolls back the stock or AR handlers sharing the dispatch.
 */
public final class SaleCashBookHandler {

    private static final Logger log = LoggerFactory.getLogger(SaleCashBookHandler.class);

    private SaleCashBookHandler() {
    }

    /** {@code SALE.FINALISED} → IN {@code SALE_TENDER} rows. */
    @Component
    public static class Finalised implements DomainEventHandler {

        static final String CONSUMER = "CASHBANK.SALE_TENDER";

        private final IdempotencyGuard guard;
        private final CashBookJournalMirror mirror;
        private final ObjectMapper objectMapper;

        public Finalised(IdempotencyGuard guard, CashBookJournalMirror mirror,
                         ObjectMapper objectMapper) {
            this.guard = guard;
            this.mirror = mirror;
            this.objectMapper = objectMapper;
        }

        @Override
        public String eventType() {
            return DomainEventType.SALE_FINALISED;
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
                SaleFinalisedPayload p = objectMapper.readValue(event.getPayload(),
                        SaleFinalisedPayload.class);
                mirror.mirrorSale(event.getCompanyId(), event.getBranchId(), p.invoiceUid(),
                        p.invoiceNumber());
            } catch (Exception ex) {
                log.warn("SaleCashBookHandler: cash-book rows for sale event uid={} company={} "
                        + "not written — error={}", event.getUid(), event.getCompanyId(),
                        ex.getMessage());
            } finally {
                restore(previous);
            }
            guard.markProcessed(CONSUMER, event.getUid());
        }
    }

    /** {@code SALE.VOIDED} → {@code SALE_REFUND} rows mirroring the GL reversal. */
    @Component
    public static class Voided implements DomainEventHandler {

        static final String CONSUMER = "CASHBANK.SALE_REFUND";

        private final IdempotencyGuard guard;
        private final CashBookJournalMirror mirror;
        private final ObjectMapper objectMapper;

        public Voided(IdempotencyGuard guard, CashBookJournalMirror mirror,
                      ObjectMapper objectMapper) {
            this.guard = guard;
            this.mirror = mirror;
            this.objectMapper = objectMapper;
        }

        @Override
        public String eventType() {
            return DomainEventType.SALE_VOIDED;
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
                SaleVoidedPayload p = objectMapper.readValue(event.getPayload(),
                        SaleVoidedPayload.class);
                mirror.mirrorSaleVoid(event.getCompanyId(), event.getBranchId(), p.invoiceUid(),
                        p.invoiceNumber());
            } catch (Exception ex) {
                log.warn("SaleCashBookHandler: cash-book rows for void event uid={} company={} "
                        + "not written — error={}", event.getUid(), event.getCompanyId(),
                        ex.getMessage());
            } finally {
                restore(previous);
            }
            guard.markProcessed(CONSUMER, event.getUid());
        }
    }

    static void restore(RequestContext.Principal previous) {
        if (previous == null) {
            RequestContext.clear();
        } else {
            RequestContext.set(previous);
        }
    }
}
