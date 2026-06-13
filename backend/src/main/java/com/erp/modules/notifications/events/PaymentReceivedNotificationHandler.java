package com.erp.modules.notifications.events;

import com.erp.modules.ar.domain.dto.PaymentReceivedPayload;
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
 * Notification handler for {@code PAYMENT.RECEIVED} → {@code PAYMENT_RECEIVED} (ADR-0024 D-4/D-8).
 * Consumes the new trigger event emitted by AR's {@code ArReceiptServiceImpl} (D-8, the one-line
 * {@code OutboxPublisher.publish} cross-module touch-point).
 */
@Component
public class PaymentReceivedNotificationHandler implements DomainEventHandler {

    static final String CONSUMER = "NOTIFICATIONS.PAYMENT_RECEIVED";

    private final NotificationDispatcherCore core;
    private final ObjectMapper               objectMapper;

    public PaymentReceivedNotificationHandler(NotificationDispatcherCore core,
                                               ObjectMapper objectMapper) {
        this.core         = core;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.PAYMENT_RECEIVED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        PaymentReceivedPayload payload = deserialise(event.getPayload());
        Map<String, String> vals = new HashMap<>();
        vals.put("customerName",    payload.customerName());
        vals.put("amountFormatted", payload.amountFormatted());
        vals.put("sourceUid",       payload.receiptUid());

        NotificationTrigger trigger = NotificationDispatcherCore.buildTrigger(
                event.getCompanyId(), event.getBranchId(),
                "PAYMENT_RECEIVED", "AR.VIEW",
                DomainEventType.AGG_AR_RECEIPT, payload.receiptUid(),
                vals, event.getUid());

        core.dispatch(CONSUMER, event, trigger);
    }

    private PaymentReceivedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, PaymentReceivedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise PaymentReceivedPayload: " + ex.getMessage(), ex);
        }
    }
}
