package com.erp.modules.sales.events;

import com.erp.modules.gl.domain.dto.JournalEntryDraft;
import com.erp.modules.gl.domain.dto.JournalEntryDraft.LineDraft;
import com.erp.modules.gl.domain.enums.GlConfigKey;
import com.erp.modules.gl.domain.enums.JournalSourceType;
import com.erp.modules.gl.service.GLConfigResolver;
import com.erp.modules.gl.service.GLPostingSafeInvoker;
import com.erp.modules.sales.domain.dto.DropshipFulfilledPayload;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.erp.platform.events.IdempotencyGuard;
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
 * Consumes {@code DROPSHIP.FULFILLED} and posts supplier-cost COGS (ADR-0029 D-11).
 *
 * <p>For a drop-ship SO line, goods are never held in own stock. COGS must be recognised at the
 * supplier cost when the supplier confirms shipment, NOT at the moving average:
 * <pre>
 *   DR COGS 5100    (qty × supplierUnitCost)
 *   CR GRNI 2150    (qty × supplierUnitCost)
 * </pre>
 * The GRNI balance is cleared when the AP bill is matched in the normal P2P flow
 * (shipped {@code BillMatchServiceImpl} — no change needed there).
 *
 * <p>Idempotency: {@link IdempotencyGuard} with consumer {@value #CONSUMER}. The GL post uses
 * {@link GLPostingSafeInvoker#postInNewTx} (REQUIRES_NEW / null-on-anomaly) so a GL anomaly
 * never poisons the dispatch TX.
 */
@Component
public class DropshipFulfilCogsHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(DropshipFulfilCogsHandler.class);

    /** Consumer name for processed_events dedup (ADR-0009 D-6). */
    static final String CONSUMER = "SALES.DROPSHIP_COGS";

    private final IdempotencyGuard    guard;
    private final GLPostingSafeInvoker glInvoker;
    private final GLConfigResolver    glConfig;
    private final ObjectMapper        objectMapper;

    public DropshipFulfilCogsHandler(IdempotencyGuard guard,
                                      GLPostingSafeInvoker glInvoker,
                                      GLConfigResolver glConfig,
                                      ObjectMapper objectMapper) {
        this.guard        = guard;
        this.glInvoker    = glInvoker;
        this.glConfig     = glConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.DROPSHIP_FULFILLED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        if (guard.alreadyProcessed(CONSUMER, event.getUid())) {
            log.debug("DropshipFulfilCogsHandler: event uid={} already processed — skipping",
                    event.getUid());
            return;
        }

        DropshipFulfilledPayload payload = deserialise(event.getPayload());

        BigDecimal qty  = payload.qtyBase();
        BigDecimal cost = payload.supplierUnitCost();
        String currency = payload.currency();

        if (qty == null || qty.compareTo(BigDecimal.ZERO) <= 0) {
            log.warn("DropshipFulfilCogsHandler: qtyBase zero/null for soLineUid={} — COGS skipped",
                    payload.salesOrderLineUid());
            guard.markProcessed(CONSUMER, event.getUid());
            return;
        }
        if (cost == null || cost.compareTo(BigDecimal.ZERO) < 0) {
            log.warn("DropshipFulfilCogsHandler: supplierUnitCost null/negative for soLineUid={} "
                    + "— COGS skipped", payload.salesOrderLineUid());
            guard.markProcessed(CONSUMER, event.getUid());
            return;
        }

        BigDecimal cogsAmount = qty.multiply(cost).setScale(4, RoundingMode.HALF_UP);

        // DR COGS 5100 / CR GRNI 2150 — reused GRNI clearing bridge (ADR-0029 D-11)
        var cogsAcct = glConfig.resolve(event.getCompanyId(), GlConfigKey.COGS);
        var grniAcct = glConfig.resolve(event.getCompanyId(), GlConfigKey.GRNI);

        LocalDate postingDate = event.getOccurredAt() != null
                ? event.getOccurredAt().atZone(ZoneOffset.UTC).toLocalDate()
                : LocalDate.now();

        var draft = new JournalEntryDraft(
                event.getCompanyId(), event.getBranchId(), postingDate,
                "Drop-ship COGS " + payload.salesOrderLineUid(),
                JournalSourceType.COGS, payload.salesOrderLineUid(),
                null, null,
                List.of(
                        new LineDraft(cogsAcct.getId(), cogsAmount, BigDecimal.ZERO,
                                currency, "Drop-ship COGS"),
                        new LineDraft(grniAcct.getId(), BigDecimal.ZERO, cogsAmount,
                                currency, "Drop-ship GRNI clearing")
                ));

        var journalEntry = glInvoker.postInNewTx(draft);
        if (journalEntry == null) {
            log.warn("DropshipFulfilCogsHandler: COGS GL post returned null for soLineUid={} "
                    + "companyId={} — GL not configured or period closed (COGS not booked)",
                    payload.salesOrderLineUid(), event.getCompanyId());
        } else {
            log.info("DropshipFulfilCogsHandler: posted COGS journalId={} for soLineUid={} "
                    + "amount={} {}",
                    journalEntry.id(), payload.salesOrderLineUid(), cogsAmount, currency);
        }

        guard.markProcessed(CONSUMER, event.getUid());
    }

    private DropshipFulfilledPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, DropshipFulfilledPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise DropshipFulfilledPayload: " + ex.getMessage(), ex);
        }
    }
}
