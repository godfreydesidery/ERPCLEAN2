package com.erp.modules.notifications.service;

/**
 * Single fan-out engine: audience → preferences → create → deliver (ADR-0024 D-7).
 * Both the dispatcher (event-driven) and the scanner (time-based) call this one method.
 */
public interface NotificationRaiser {

    /**
     * Fan out a trigger to all audience members across enabled channels.
     * Must be called inside a transaction (the dispatcher's per-event TX or the scanner's
     * per-condition TX). The in-app rows commit with the caller's TX; email is async after commit.
     *
     * @param trigger the fully-resolved trigger descriptor
     */
    void raise(NotificationTrigger trigger);
}
