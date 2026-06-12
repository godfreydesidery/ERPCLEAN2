package com.erp.modules.notifications.events;

import com.erp.modules.sales.domain.dto.DeliveryConfirmedPayload;
import com.erp.modules.notifications.service.NotificationTrigger;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.DomainEventHandler;
import com.erp.platform.events.DomainEventType;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.HashMap;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Notification handler for {@code DELIVERY.CONFIRMED} → {@code DELIVERY_CONFIRMED} (ADR-0024 D-4).
 */
@Component
public class DeliveryConfirmedNotificationHandler implements DomainEventHandler {

    static final String CONSUMER = "NOTIFICATIONS.DELIVERY_CONFIRMED";

    private final NotificationDispatcherCore core;
    private final ObjectMapper               objectMapper;

    public DeliveryConfirmedNotificationHandler(NotificationDispatcherCore core,
                                                 ObjectMapper objectMapper) {
        this.core         = core;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.DELIVERY_CONFIRMED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        DeliveryConfirmedPayload payload = deserialise(event.getPayload());
        Map<String, String> vals = new HashMap<>();
        vals.put("deliveryReference", payload.deliveryUid());
        vals.put("customerName", "Customer");
        vals.put("sourceUid", payload.deliveryUid());

        NotificationTrigger trigger = NotificationDispatcherCore.buildTrigger(
                event.getCompanyId(), event.getBranchId(),
                "DELIVERY_CONFIRMED", "SALES.DELIVERY.VIEW",
                DomainEventType.AGG_DELIVERY, payload.deliveryUid(),
                vals, event.getUid());

        core.dispatch(CONSUMER, event, trigger);
    }

    private DeliveryConfirmedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, DeliveryConfirmedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise DeliveryConfirmedPayload: " + ex.getMessage(), ex);
        }
    }
}
