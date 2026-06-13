package com.erp.modules.notifications.service;

import com.erp.modules.notifications.domain.dto.NotificationDto;
import com.erp.modules.notifications.domain.dto.UnreadCountDto;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/**
 * In-app inbox reads and mark-read operations (ADR-0024 D-11).
 */
public interface NotificationInboxService {

    Page<NotificationDto> listInbox(Long userId, Long companyId,
                                     boolean unreadOnly, Pageable pageable);

    UnreadCountDto unreadCount(Long userId, Long companyId);

    void markRead(String uid, Long callerUserId, Long callerCompanyId);

    void markAllRead(Long userId, Long companyId);
}
