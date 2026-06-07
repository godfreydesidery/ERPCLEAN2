package com.erp.platform.events;

/**
 * Lifecycle states for a {@link DomainEvent} row (ADR-0009 D-2).
 *
 * <ul>
 *   <li>{@code PENDING} — written by the producer; not yet delivered to any handler.</li>
 *   <li>{@code DISPATCHED} — all registered handlers succeeded (or none were registered).</li>
 *   <li>{@code FAILED} — handler threw on every attempt up to the retry cap; row is parked.</li>
 * </ul>
 */
public enum DomainEventStatus {
    PENDING,
    DISPATCHED,
    FAILED
}
