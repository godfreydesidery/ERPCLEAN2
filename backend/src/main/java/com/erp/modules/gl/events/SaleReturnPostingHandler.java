package com.erp.modules.gl.events;

import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.sales.domain.dto.InvoicePostingTotalsDto;
import com.erp.modules.sales.domain.dto.PosSaleReturnedPayload;
import com.erp.modules.sales.service.SalesInvoiceService;
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
 * Consumes {@code POS.SALE.RETURNED} — posts the partial reversal of the sale's revenue and VAT
 * against Cash (ADR-0072): a {@code SALES_REVERSAL} journal with {@code source_ref} = the RETURN's uid.
 *
 * <p>The amounts are the return's own (carried on the payload — the return rows are sales-owned and GL
 * may not read them); the sale's dimension and project tags are re-read from the invoice, as
 * {@link SalesPostingHandler} does, so the revenue reversal lands on the same cost centre /
 * department / project the revenue did.
 *
 * <p>Same isolation contract as {@link SaleVoidingHandler}: the post runs in its own REQUIRES_NEW
 * transaction and never poisons the dispatch; a failure is recorded as a GL posting exception
 * (kind {@code SALE_RETURN}, re-postable) and the event is still marked processed.
 */
// ARC-08: runs before the cash book's handler for the same event, which mirrors the journal this
// handler posts (in its own committed transaction). Unordered handlers keep their relative order.
@org.springframework.core.annotation.Order(0)
@Component
public class SaleReturnPostingHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(SaleReturnPostingHandler.class);

    static final String CONSUMER = "GL.POS_SALE_RETURN";

    private final IdempotencyGuard guard;
    private final SalesInvoiceService salesInvoiceService;
    private final GLPostingSafeInvoker safeInvoker;
    private final ObjectMapper objectMapper;
    private final CompanyCalendar calendar;

    public SaleReturnPostingHandler(IdempotencyGuard guard,
                                    SalesInvoiceService salesInvoiceService,
                                    GLPostingSafeInvoker safeInvoker,
                                    ObjectMapper objectMapper,
                                    CompanyCalendar calendar) {
        this.guard               = guard;
        this.salesInvoiceService = salesInvoiceService;
        this.safeInvoker         = safeInvoker;
        this.objectMapper        = objectMapper;
        this.calendar            = calendar;
    }

    @Override
    public String eventType() {
        return DomainEventType.POS_SALE_RETURNED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("SaleReturnPostingHandler: event uid={} already processed — skipping",
                    event.getUid());
            return;
        }

        PosSaleReturnedPayload payload = deserialise(event.getPayload());
        Long companyId = event.getCompanyId();

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(RequestContext.Principal.system(companyId, event.getBranchId()));
        try {
            InvoicePostingTotalsDto sale = salesInvoiceService
                    .findPostingTotalsByUidAndCompany(payload.invoiceUid(), companyId)
                    .orElse(null);
            LocalDate postingDate = calendar.dateOf(companyId, payload.returnedAt());
            safeInvoker.postSaleReturnInNewTx(companyId, event.getBranchId(), payload.returnUid(),
                    payload.returnNumber(), payload.currency(), payload.grossAmount(),
                    payload.netAmount(), payload.vatAmount(), postingDate,
                    sale == null ? null : sale.costCentreValueId(),
                    sale == null ? null : sale.departmentValueId(),
                    sale == null ? null : sale.projectId(),
                    sale == null ? null : sale.projectTaskId());
        } catch (Exception ex) {
            // Never poison the dispatch TX for the stock and cash-book handlers sharing it.
            log.warn("SaleReturnPostingHandler: SALES_REVERSAL failed for return uid={} company={} — "
                            + "anomaly recorded, marking event processed. error={} event uid={}",
                    payload.returnUid(), companyId, ex.getMessage(), event.getUid());
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }

        guard.markProcessed(CONSUMER, event.getUid());
    }

    private PosSaleReturnedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, PosSaleReturnedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise PosSaleReturnedPayload: " + ex.getMessage(), ex);
        }
    }
}
