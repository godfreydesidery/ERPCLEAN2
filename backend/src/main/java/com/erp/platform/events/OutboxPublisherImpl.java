package com.erp.platform.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link OutboxPublisher} implementation (ADR-0009 D-3).
 *
 * <p><strong>Atomicity invariant:</strong> {@code Propagation.MANDATORY} — this method MUST run
 * inside the caller's transaction. There is deliberately NO {@code REQUIRES_NEW}: if the business
 * TX rolls back, the event row rolls back with it. If called outside a transaction the call fails
 * fast (same discipline as {@code AuditServiceImpl}).
 *
 * <p>Payload is serialised to a JSON string via Jackson (the same {@code ObjectMapper} the API
 * uses) and stored as the JSONB {@code payload} column.
 */
@Component
class OutboxPublisherImpl implements OutboxPublisher {

    private final DomainEventRepository repository;
    private final ObjectMapper objectMapper;

    OutboxPublisherImpl(DomainEventRepository repository, ObjectMapper objectMapper) {
        this.repository   = repository;
        this.objectMapper = objectMapper;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public String publish(String eventType,
                          String aggregateType,
                          Long aggregateId,
                          String aggregateUid,
                          Long companyId,
                          Long branchId,
                          Object payload) {
        String payloadJson = serialize(payload);
        DomainEvent event = new DomainEvent(
                eventType, aggregateType, aggregateId, aggregateUid,
                companyId, branchId, payloadJson);
        DomainEvent saved = repository.save(event);
        return saved.getUid();
    }

    private String serialize(Object payload) {
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialise outbox payload to JSON", e);
        }
    }
}
