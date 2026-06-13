package com.erp.modules.notifications.events;

import com.erp.modules.stock.domain.dto.StockReceivedPayload;
import com.erp.modules.notifications.service.NotificationTrigger;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notification handler for {@code STOCK.RECEIVED} → {@code GOODS_RECEIVED} (ADR-0024 D-4).
 */
@Component
public class StockReceivedNotificationHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(StockReceivedNotificationHandler.class);
    static final String CONSUMER = "NOTIFICATIONS.STOCK_RECEIVED";

    private final NotificationDispatcherCore core;
    private final ObjectMapper               objectMapper;

    public StockReceivedNotificationHandler(NotificationDispatcherCore core,
                                             ObjectMapper objectMapper) {
        this.core         = core;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.STOCK_RECEIVED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        StockReceivedPayload payload = deserialise(event.getPayload());
        Map<String, String> vals = new HashMap<>();
        vals.put("receiptReference", payload.receiptUid());
        vals.put("branchName", "Branch#" + event.getBranchId());
        vals.put("sourceUid", payload.receiptUid());

        NotificationTrigger trigger = NotificationDispatcherCore.buildTrigger(
                event.getCompanyId(), event.getBranchId(),
                "GOODS_RECEIVED", "STOCK.VIEW",
                DomainEventType.AGG_GOODS_RECEIPT, payload.receiptUid(),
                vals, event.getUid());

        core.dispatch(CONSUMER, event, trigger);
    }

    private StockReceivedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, StockReceivedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise StockReceivedPayload: " + ex.getMessage(), ex);
        }
    }
}
