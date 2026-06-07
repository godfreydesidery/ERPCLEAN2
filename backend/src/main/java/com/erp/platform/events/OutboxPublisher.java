package com.erp.platform.events;

/**
 * Producer API for the transactional outbox (ADR-0009 D-3).
 *
 * <p>Callers invoke {@code publish} <em>inside their own {@code @Transactional} service method</em>
 * so the PENDING {@link DomainEvent} row commits atomically with the business change — if the
 * business TX rolls back, the event row rolls back with it. The implementation must
 * <strong>not</strong> open its own transaction ({@code REQUIRES_NEW} would break this guarantee).
 */
public interface OutboxPublisher {

    /**
     * Inserts a PENDING {@code domain_events} row in the <em>caller's current transaction</em>.
     * The {@code payload} POJO is serialised to JSONB by the implementation via Jackson.
     *
     * @param eventType     canonical type string — use a {@link DomainEventType} constant
     * @param aggregateType aggregate kind — use a {@link DomainEventType} {@code AGG_*} constant
     * @param aggregateId   internal id of the producing aggregate
     * @param aggregateUid  stable uid of the producing aggregate
     * @param companyId     originating company scope (consumer uses this to set tenant context)
     * @param branchId      originating branch scope; nullable for company-level events
     * @param payload       POJO serialised to JSONB payload; use a typed record per module
     * @return the new event's uid (stable external identifier)
     */
    String publish(String eventType,
                   String aggregateType,
                   Long aggregateId,
                   String aggregateUid,
                   Long companyId,
                   Long branchId,
                   Object payload);
}
