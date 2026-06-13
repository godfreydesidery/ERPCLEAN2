package com.erp.modules.notifications.service;

import com.erp.modules.notifications.domain.dto.NotificationDto;
import com.erp.modules.notifications.domain.dto.UnreadCountDto;
import com.erp.modules.notifications.domain.entity.Notification;
import com.erp.modules.notifications.repository.NotificationRepository;
import com.erp.platform.common.api.ForbiddenException;
import com.erp.platform.common.api.NotFoundException;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * In-app inbox reads + mark-read (ADR-0024 D-11).
 * Enforces recipient-is-me (BR-NOTIF-04) at the service level.
 */
@Service
@Transactional
public class NotificationInboxServiceImpl implements NotificationInboxService {

    private final NotificationRepository notifications;

    public NotificationInboxServiceImpl(NotificationRepository notifications) {
        this.notifications = notifications;
    }

    @Override
    @Transactional(readOnly = true)
    public Page<NotificationDto> listInbox(Long userId, Long companyId,
                                            boolean unreadOnly, Pageable pageable) {
        Page<Notification> page = unreadOnly
                ? notifications.findUnreadInboxByUserAndCompany(userId, companyId, pageable)
                : notifications.findInboxByUserAndCompany(userId, companyId, pageable);
        return page.map(NotificationInboxServiceImpl::toDto);
    }

    @Override
    @Transactional(readOnly = true)
    public UnreadCountDto unreadCount(Long userId, Long companyId) {
        return new UnreadCountDto(notifications.countUnread(userId, companyId));
    }

    @Override
    public void markRead(String uid, Long callerUserId, Long callerCompanyId) {
        Notification n = notifications.findByUid(uid)
                .orElseThrow(() -> new NotFoundException("Notification not found: " + uid));
        // Enforce recipient-is-me (BR-NOTIF-04)
        if (!n.getRecipientUserId().equals(callerUserId)) {
            throw ForbiddenException.notPermitted();
        }
        if (!n.getCompanyId().equals(callerCompanyId)) {
            throw ForbiddenException.notPermitted();
        }
        if (!n.isRead()) {
            n.markRead(Instant.now());
            notifications.save(n);
        }
    }

    @Override
    public void markAllRead(Long userId, Long companyId) {
        List<Notification> unread = notifications.findAllUnreadByUserAndCompany(userId, companyId);
        Instant now = Instant.now();
        unread.forEach(n -> n.markRead(now));
        notifications.saveAll(unread);
    }

    static NotificationDto toDto(Notification n) {
        return new NotificationDto(
                n.getId(), n.getUid(), n.getCompanyId(), n.getTypeKey(),
                n.getChannel(), n.getSeverity(), n.getTitle(), n.getBody(),
                n.getSourceAggregateType(), n.getSourceUid(), n.getLinkRoute(),
                n.isRead(), n.getReadAt(), n.getCreatedAt());
    }
}
