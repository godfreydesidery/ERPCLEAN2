package com.erp.modules.ar.events;

import com.erp.modules.ar.service.ArCreditNoteService;
import com.erp.modules.sales.domain.dto.InvoicePostingTotalsDto;
import com.erp.modules.sales.domain.dto.SaleVoidedPayload;
import com.erp.modules.sales.service.SalesInvoiceService;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Consumes {@code SALE.VOIDED} for AR (SAL-03): a voided credit sale's open item is cleared with
 * a {@code SALE_VOID} credit note ({@link com.erp.modules.ar.domain.enums.ArCreditNoteOrigin})
 * applied to it, so the debtors list, statement, ageing and credit-limit exposure stop showing
 * the voided invoice as owed and the AR subledger keeps agreeing with AR control.
 *
 * <p>No GL is posted here: {@code SaleVoidingHandler} already reverses the sale's journal entry,
 * AR control leg included. A cash sale has no open item, so the event is a no-op for it.
 *
 * <p>Mirrors {@link ArSalePostedHandler}: MANDATORY TX, IdempotencyGuard, system RequestContext,
 * anomalies logged and the event still marked processed. The credit note itself is written in
 * its own transaction ({@link ArCreditNoteService#raiseForSaleVoid}), so a failure there cannot
 * mark the shared dispatch TX rollback-only and undo the GL/Stock handlers of the same event; a
 * retried event finds the note already raised and does nothing.
 */
@Component
public class ArSaleVoidedHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ArSaleVoidedHandler.class);

    static final String CONSUMER = "AR.SALE_VOID";

    private final IdempotencyGuard guard;
    private final SalesInvoiceService salesInvoiceService;
    private final ArCreditNoteService creditNotes;
    private final ObjectMapper objectMapper;

    public ArSaleVoidedHandler(IdempotencyGuard guard,
                               SalesInvoiceService salesInvoiceService,
                               ArCreditNoteService creditNotes,
                               ObjectMapper objectMapper) {
        this.guard               = guard;
        this.salesInvoiceService = salesInvoiceService;
        this.creditNotes         = creditNotes;
        this.objectMapper        = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.SALE_VOIDED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("ArSaleVoidedHandler: event uid={} already processed — skipping", event.getUid());
            return;
        }

        Long companyId = event.getCompanyId();
        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(companyId, event.getBranchId()));
        try {
            SaleVoidedPayload payload = objectMapper.readValue(event.getPayload(),
                    SaleVoidedPayload.class);
            InvoicePostingTotalsDto totals = salesInvoiceService
                    .findPostingTotalsByUidAndCompany(payload.invoiceUid(), companyId)
                    .orElse(null);
            // Same date basis as the GL reversal the void posts (SaleVoidingHandler).
            LocalDate noteDate = LocalDate.ofInstant(Instant.now(), ZoneOffset.UTC);
            creditNotes.raiseForSaleVoid(companyId, payload.invoiceUid(), payload.invoiceNumber(),
                            noteDate,
                            totals != null ? totals.grossTotalAmount() : null,
                            totals != null ? totals.vatTotalAmount() : null)
                    .ifPresent(cnUid -> log.debug(
                            "ArSaleVoidedHandler: SALE_VOID credit note uid={} cleared invoice uid={}",
                            cnUid, payload.invoiceUid()));
        } catch (Exception ex) {
            log.warn("ArSaleVoidedHandler: clearing the AR open item failed for company={} — "
                            + "anomaly recorded, marking processed. error={} event uid={}",
                    companyId, ex.getMessage(), event.getUid());
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }

        guard.markProcessed(CONSUMER, event.getUid());
    }
}
