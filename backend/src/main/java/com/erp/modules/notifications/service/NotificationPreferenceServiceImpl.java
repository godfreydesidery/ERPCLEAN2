package com.erp.modules.notifications.service;

import com.erp.modules.notifications.domain.dto.NotificationPreferenceDto;
import com.erp.modules.notifications.domain.dto.SetPreferenceRequest;
import com.erp.modules.notifications.domain.entity.NotificationPreference;
import com.erp.modules.notifications.repository.NotificationPreferenceRepository;
import com.erp.modules.notifications.repository.NotificationTypeRepository;
import com.erp.platform.common.api.NotFoundException;
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
    private final NotificationTypeRepository       types;

    public NotificationPreferenceServiceImpl(NotificationPreferenceRepository preferences,
                                              NotificationTypeRepository types) {
        this.preferences = preferences;
        this.types       = types;
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
        // Defect 1: guard against unknown typeKey — reject before creating an orphan row.
        if (types.findByCompanyIdAndTypeKey(companyId, typeKey).isEmpty()) {
            // companyId and typeKey intentionally not surfaced (error-hygiene rule)
            throw new NotFoundException("The specified notification type was not found.");
        }
        // Defect 2: both fields are boolean primitives; an empty body {} deserialises to
        // muted=false/channelsEnabled=null — treat a null channelsEnabled as an ambiguous body.
        if (!req.muted() && req.channelsEnabled() == null) {
            throw new IllegalArgumentException(
                    "Request body must supply at least one of 'muted' or 'channelsEnabled' "
                    + "with an explicit value (empty body is not accepted).");
        }
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
