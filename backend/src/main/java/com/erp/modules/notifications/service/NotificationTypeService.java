package com.erp.modules.notifications.service;

import com.erp.modules.notifications.domain.dto.NotificationTypeDto;
import com.erp.modules.notifications.domain.dto.SetCompanyTypeStateRequest;
import java.util.List;

/**
 * Notification type catalogue read + per-company toggle (ADR-0024 D-11).
 */
public interface NotificationTypeService {

    List<NotificationTypeDto> listForCompany(Long companyId);

    NotificationTypeDto setCompanyTypeState(String typeKey, Long companyId,
                                             SetCompanyTypeStateRequest req, Long actorId);
}
