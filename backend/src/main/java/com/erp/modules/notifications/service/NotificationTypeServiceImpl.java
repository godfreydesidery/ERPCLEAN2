package com.erp.modules.notifications.service;

import com.erp.modules.notifications.domain.dto.NotificationTypeDto;
import com.erp.modules.notifications.domain.dto.SetCompanyTypeStateRequest;
import com.erp.modules.notifications.domain.entity.NotificationType;
import com.erp.modules.notifications.repository.NotificationTypeRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Type catalogue read + audited per-company toggle (ADR-0024 D-11, NFR-NOTIF-03).
 */
@Service
@Transactional
public class NotificationTypeServiceImpl implements NotificationTypeService {

    private final NotificationTypeRepository types;
    private final AuditService               audit;

    public NotificationTypeServiceImpl(NotificationTypeRepository types,
                                        AuditService audit) {
        this.types = types;
        this.audit = audit;
    }

    @Override
    @Transactional(readOnly = true)
    public List<NotificationTypeDto> listForCompany(Long companyId) {
        return types.findByCompanyId(companyId).stream()
                .map(NotificationTypeServiceImpl::toDto).toList();
    }

    @Override
    public NotificationTypeDto setCompanyTypeState(String typeKey, Long companyId,
                                                    SetCompanyTypeStateRequest req, Long actorId) {
        NotificationType type = types.findByCompanyIdAndTypeKey(companyId, typeKey)
                .orElseThrow(() -> new NotFoundException(
                        "NotificationType not found: company=" + companyId + " typeKey=" + typeKey));
        type.setCompanyEnabled(req.enabled());
        type.touch(Instant.now(), actorId);
        types.save(type);

        audit.record(AuditEvent.of(AuditActions.NOTIFICATION_TYPE_TOGGLE,
                                   "notification_types", type.getId(), type.getUid())
                .detail(Map.of("typeKey", typeKey,
                               "companyId", String.valueOf(companyId),
                               "enabled", String.valueOf(req.enabled()))));

        return toDto(type);
    }

    static NotificationTypeDto toDto(NotificationType t) {
        return new NotificationTypeDto(
                t.getId(), t.getUid(), t.getCompanyId(), t.getTypeKey(),
                t.getDisplayName(), t.getDescription(), t.getAudiencePermission(),
                t.isBranchScoped(), t.getDefaultChannels(), t.getSeverity(),
                t.getTitleTemplate(), t.getBodyTemplate(), t.getLinkRouteTemplate(),
                t.isCompanyEnabled(), t.getStatus().name());
    }
}
