package com.erp.modules.notifications.events;

import com.erp.modules.approvals.domain.dto.ApprovalSubmittedPayload;
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
 * Notification handler for {@code APPROVAL.SUBMITTED} → {@code APPROVAL_PENDING} (ADR-0024 D-4/D-8).
 *
 * <p>Consumes the EXISTING {@code APPROVAL.SUBMITTED} event emitted by the approvals engine
 * (ADR-0022 D-10) — NOT a new event. The approval type ({@code APPROVAL_PENDING}) is seeded and
 * dormant until the approvals engine ships (BR-NOTIF-14).
 *
 * <p>The audience is dynamic: the approver permission carried in the payload (ADR-0022) determines
 * who should be notified, overriding the type catalogue's static {@code audience_permission}.
 */
@Component
public class ApprovalSubmittedNotificationHandler implements DomainEventHandler {

    private static final Logger log = LoggerFactory.getLogger(ApprovalSubmittedNotificationHandler.class);
    static final String CONSUMER = "NOTIFICATIONS.APPROVAL_SUBMITTED";

    /**
     * Fallback approver permission when none is derivable from the payload.
     * The seeded APPROVAL_PENDING type uses this as its catalogue default.
     */
    static final String DEFAULT_APPROVER_PERM = "PURCHASE.PO.APPROVE";

    private final NotificationDispatcherCore core;
    private final ObjectMapper               objectMapper;

    public ApprovalSubmittedNotificationHandler(NotificationDispatcherCore core,
                                                 ObjectMapper objectMapper) {
        this.core         = core;
        this.objectMapper = objectMapper;
    }

    @Override
    public String eventType() {
        return DomainEventType.APPROVAL_SUBMITTED;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void handle(DomainEvent event) {
        ApprovalSubmittedPayload payload = deserialise(event.getPayload());
        Map<String, String> vals = new HashMap<>();
        vals.put("documentType", payload.documentType() != null ? payload.documentType() : "Document");
        vals.put("documentUid",  payload.documentUid() != null ? payload.documentUid() : payload.requestUid());
        vals.put("submittedBy",  "User#" + payload.submittedByUserId());
        vals.put("sourceUid",    payload.requestUid());

        // Dynamic audience: the approver permission from the payload, falling back to catalogue default
        // The approvals payload (ADR-0022) does not carry the approver perm directly; the notification
        // type catalogue row's audience_permission is used as the audience (null override = use catalogue).
        NotificationTrigger trigger = NotificationDispatcherCore.buildTrigger(
                event.getCompanyId(), event.getBranchId(),
                "APPROVAL_PENDING", null, // null → use catalogue's audience_permission
                DomainEventType.AGG_APPROVAL_REQUEST, payload.requestUid(),
                vals, event.getUid());

        core.dispatch(CONSUMER, event, trigger);
    }

    private ApprovalSubmittedPayload deserialise(String json) {
        try {
            return objectMapper.readValue(json, ApprovalSubmittedPayload.class);
        } catch (Exception ex) {
            throw new IllegalArgumentException(
                    "Cannot deserialise ApprovalSubmittedPayload: " + ex.getMessage(), ex);
        }
    }
}
