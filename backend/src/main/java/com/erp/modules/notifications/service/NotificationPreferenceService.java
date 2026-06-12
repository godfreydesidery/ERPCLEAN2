package com.erp.modules.notifications.service;

import com.erp.modules.notifications.domain.dto.NotificationPreferenceDto;
import com.erp.modules.notifications.domain.dto.SetPreferenceRequest;
import java.util.List;

/**
 * Per-user notification preference read/set (ADR-0024 D-11).
 */
public interface NotificationPreferenceService {

    List<NotificationPreferenceDto> listPreferences(Long userId, Long companyId);

    NotificationPreferenceDto setPreference(Long userId, Long companyId,
                                             String typeKey, SetPreferenceRequest req);
}
