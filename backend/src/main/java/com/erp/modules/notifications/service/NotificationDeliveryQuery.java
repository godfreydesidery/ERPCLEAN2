package com.erp.modules.notifications.service;

import com.erp.modules.notifications.domain.dto.NotificationDeliveryDto;
import com.erp.modules.notifications.domain.entity.NotificationDelivery;
import com.erp.modules.notifications.repository.NotificationDeliveryRepository;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Admin delivery-log read (ADR-0024 D-11, FR-NOTIF-12).
 * Not a full interface+impl pair — it is a query-only service (no mutations).
 */
@Service
@Transactional(readOnly = true)
public class NotificationDeliveryQuery {

    private final NotificationDeliveryRepository deliveries;

    public NotificationDeliveryQuery(NotificationDeliveryRepository deliveries) {
        this.deliveries = deliveries;
    }

    public Page<NotificationDeliveryDto> listByCompany(Long companyId, Pageable pageable) {
        return deliveries.findByCompanyId(companyId, pageable)
                .map(NotificationDeliveryQuery::toDto);
    }

    public Page<NotificationDeliveryDto> listByCompanyAndChannel(Long companyId, String channel,
                                                                   Pageable pageable) {
        return deliveries.findByCompanyIdAndChannel(companyId, channel, pageable)
                .map(NotificationDeliveryQuery::toDto);
    }

    public Page<NotificationDeliveryDto> listByCompanyAndOutcome(Long companyId, String outcome,
                                                                   Pageable pageable) {
        return deliveries.findByCompanyIdAndOutcome(companyId, outcome, pageable)
                .map(NotificationDeliveryQuery::toDto);
    }

    static NotificationDeliveryDto toDto(NotificationDelivery d) {
        return new NotificationDeliveryDto(
                d.getId(), d.getUid(), d.getNotificationId(), d.getCompanyId(),
                d.getTypeKey(), d.getRecipientUserId(), d.getChannel(),
                d.getOutcome(), d.getSuppressionReason(), d.getAttemptNo(),
                d.getError(), d.getTriggerKey(), d.getAttemptedAt(), d.getCompletedAt());
    }
}
