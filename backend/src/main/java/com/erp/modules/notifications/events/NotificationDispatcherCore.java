package com.erp.modules.notifications.events;

import com.erp.modules.notifications.service.NotificationRaiser;
import com.erp.modules.notifications.service.NotificationTrigger;
import com.erp.platform.events.DomainEvent;
import com.erp.platform.events.IdempotencyGuard;
import com.erp.platform.security.RequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * Shared dispatcher core for the per-type handler beans (ADR-0024 D-4).
 *
 * <p>Each thin handler bean delegates here. The core:
 * 1. {@code IdempotencyGuard.alreadyProcessed} (BR-NOTIF-07).
 * 2. Sets SYSTEM context from the event's company/branch (no JWT, D-1).
 * 3. Calls {@link NotificationRaiser#raise} with the handler-supplied trigger.
 * 4. {@code IdempotencyGuard.markProcessed} in the same TX.
 */
@Component
public class NotificationDispatcherCore {

    private static final Logger log = LoggerFactory.getLogger(NotificationDispatcherCore.class);

    private final IdempotencyGuard guard;
    private final NotificationRaiser raiser;

    public NotificationDispatcherCore(IdempotencyGuard guard, NotificationRaiser raiser) {
        this.guard  = guard;
        this.raiser = raiser;
    }

    /**
     * Dispatch a trigger event. Called within the {@code DomainEventDispatcher}'s per-event TX
     * ({@code Propagation.MANDATORY} on the calling handler).
     *
     * @param consumer   idempotency consumer key, e.g. {@code "NOTIFICATIONS.STOCK_RECEIVED"}
     * @param event      the outbox domain event
     * @param trigger    the fully-built trigger to raise (caller-supplied)
     */
    public void dispatch(String consumer, DomainEvent event, NotificationTrigger trigger) {
        if (guard.alreadyProcessed(consumer, event.getUid())) {
            log.debug("{}: already processed event uid={} — skipping", consumer, event.getUid());
            return;
        }

        RequestContext.Principal previous = RequestContext.get();
        RequestContext.set(new RequestContext.Principal(
                null, "SYSTEM", false, event.getCompanyId(), event.getBranchId(), null));
        try {
            raiser.raise(trigger);
        } finally {
            if (previous == null) {
                RequestContext.clear();
            } else {
                RequestContext.set(previous);
            }
        }

        guard.markProcessed(consumer, event.getUid());
    }

    // -----------------------------------------------------------------------
    // Template-value helpers used by the individual handler beans
    // -----------------------------------------------------------------------

    /** Builds a minimal trigger with no template values for handlers that re-read. */
    public static NotificationTrigger buildTrigger(Long companyId, Long branchId,
                                                    String typeKey, String audiencePermission,
                                                    String sourceAggType, String sourceUid,
                                                    Map<String, String> vals, String triggerKey) {
        return new NotificationTrigger(companyId, branchId, typeKey, audiencePermission,
                                       sourceAggType, sourceUid, vals, triggerKey);
    }
}
