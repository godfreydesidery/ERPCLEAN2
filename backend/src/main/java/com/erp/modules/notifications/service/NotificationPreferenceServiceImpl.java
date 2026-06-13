package com.erp.modules.notifications.service;

import com.erp.modules.notifications.domain.dto.NotificationPreferenceDto;
import com.erp.modules.notifications.domain.dto.SetPreferenceRequest;
import com.erp.modules.notifications.domain.entity.NotificationPreference;
import com.erp.modules.notifications.repository.NotificationPreferenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Per-user preference read/upsert (ADR-0024 D-11).
 * Row is created lazily on first set (BR-NOTIF-06).
 */
@Service
@Transactional
public class NotificationPreferenceServiceImpl implements NotificationPreferenceService {

    private final NotificationPreferenceRepository preferences;

    public NotificationPreferenceServiceImpl(NotificationPreferenceRepository preferences) {
        this.preferences = preferences;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationPreferenceDto> listPreferences(Long userId, Long companyId) {
        return preferences.findByCompanyIdAndUserId(companyId, userId)
                .stream().map(NotificationPreferenceServiceImpl::toDto).toList();
    }

    @Override
    public NotificationPreferenceDto setPreference(Long userId, Long companyId,
                                                    String typeKey, SetPreferenceRequest req) {
        Optional<NotificationPreference> existing =
                preferences.findByCompanyIdAndUserIdAndTypeKey(companyId, userId, typeKey);
        NotificationPreference pref;
        if (existing.isPresent()) {
            pref = existing.get();
            pref.update(req.muted(), req.channelsEnabled(), Instant.now(), userId);
        } else {
            pref = new NotificationPreference(companyId, userId, typeKey,
                                               req.muted(), req.channelsEnabled(), userId);
        }
        return toDto(preferences.save(pref));
    }

    static NotificationPreferenceDto toDto(NotificationPreference p) {
        return new NotificationPreferenceDto(
                p.getId(), p.getUid(), p.getCompanyId(), p.getUserId(),
                p.getTypeKey(), p.isMuted(), p.getChannelsEnabled());
    }
}
