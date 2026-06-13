package com.erp.modules.notifications.service;

import com.erp.modules.iam.repository.AppUserRepository;
import com.erp.modules.notifications.domain.entity.Notification;
import com.erp.modules.notifications.domain.entity.NotificationDelivery;
import com.erp.modules.notifications.domain.entity.NotificationPreference;
import com.erp.modules.notifications.domain.entity.NotificationType;
import com.erp.modules.notifications.repository.NotificationDeliveryRepository;
import com.erp.modules.notifications.repository.NotificationPreferenceRepository;
import com.erp.modules.notifications.repository.NotificationRepository;
import com.erp.modules.notifications.repository.NotificationTypeRepository;
import com.erp.platform.common.domain.MasterStatus;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Single fan-out engine (ADR-0024 D-7).
 *
 * <p>Step sequence:
 * 1. Load the NotificationType for (company, typeKey). If inactive or company_enabled=false →
 *    log SUPPRESSED (COMPANY_TYPE_OFF) and return.
 * 2. Resolve audience via {@link AudienceResolver}. Zero recipients → optional NO_AUDIENCE
 *    diagnostic; return.
 * 3. Per recipient, per enabled channel:
 *    - muted / channel-disabled → SUPPRESSED delivery log.
 *    - idempotent insert of the notification row (ON CONFLICT DO NOTHING via unique constraint).
 *    - IN_APP → SENT immediately.
 *    - EMAIL → PENDING delivery row + async send via {@link EmailSender}.
 * 4. Render title/body/link via {@link TemplateRenderer} (D-5).
 */
@Service
public class NotificationRaiserImpl implements NotificationRaiser {

    private static final Logger log = LoggerFactory.getLogger(NotificationRaiserImpl.class);

    private static final String CHANNEL_IN_APP = "IN_APP";
    private static final String CHANNEL_EMAIL  = "EMAIL";

    private final NotificationTypeRepository        types;
    private final NotificationRepository            notifications;
    private final NotificationPreferenceRepository  preferences;
    private final NotificationDeliveryRepository    deliveries;
    private final AppUserRepository                 users;
    private final AudienceResolver                  audienceResolver;
    private final TemplateRenderer                  templateRenderer;
    private final Optional<EmailSender>              emailSender;

    public NotificationRaiserImpl(NotificationTypeRepository types,
                                   NotificationRepository notifications,
                                   NotificationPreferenceRepository preferences,
                                   NotificationDeliveryRepository deliveries,
                                   AppUserRepository users,
                                   AudienceResolver audienceResolver,
                                   TemplateRenderer templateRenderer,
                                   Optional<EmailSender> emailSender) {
        this.types            = types;
        this.notifications    = notifications;
        this.preferences      = preferences;
        this.deliveries       = deliveries;
        this.users            = users;
        this.audienceResolver = audienceResolver;
        this.templateRenderer = templateRenderer;
        this.emailSender      = emailSender;
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public void raise(NotificationTrigger trigger) {
        // 1. Load type
        Optional<NotificationType> typeOpt =
                types.findByCompanyIdAndTypeKey(trigger.companyId(), trigger.typeKey());
        if (typeOpt.isEmpty()) {
            log.warn("NotificationRaiser: no notification_types row for company={} typeKey={}; skipping",
                     trigger.companyId(), trigger.typeKey());
            return;
        }
        NotificationType type = typeOpt.get();

        if (type.getStatus() != MasterStatus.ACTIVE || !type.isCompanyEnabled()) {
            log.debug("NotificationRaiser: type {} company={} is disabled/inactive — suppressed",
                      trigger.typeKey(), trigger.companyId());
            NotificationDelivery diag = NotificationDelivery.suppressed(
                    trigger.companyId(), trigger.typeKey(), null, CHANNEL_IN_APP,
                    "COMPANY_TYPE_OFF", trigger.triggerKey());
            deliveries.save(diag);
            return;
        }

        // Determine audience permission (use override from trigger for dynamic audience, else type's)
        String audiencePermission = trigger.audiencePermission() != null
                ? trigger.audiencePermission()
                : type.getAudiencePermission();

        // 2. Audience resolution
        List<Long> recipientIds = audienceResolver.recipients(
                trigger.companyId(), trigger.branchId(),
                audiencePermission, type.isBranchScoped());

        if (recipientIds.isEmpty()) {
            log.debug("NotificationRaiser: no recipients for type={} company={} perm={} — NO_AUDIENCE",
                      trigger.typeKey(), trigger.companyId(), audiencePermission);
            NotificationDelivery diag = NotificationDelivery.suppressed(
                    trigger.companyId(), trigger.typeKey(), null, CHANNEL_IN_APP,
                    "NO_AUDIENCE", trigger.triggerKey());
            deliveries.save(diag);
            return;
        }

        // Determine effective channels from the type's default_channels
        List<String> typeChannels = parseChannels(type.getDefaultChannels());

        // Render title, body, link once (same for all recipients)
        Map<String, String> vals       = trigger.templateValues() != null ? trigger.templateValues() : Map.of();
        String renderedTitle           = templateRenderer.render(type.getTitleTemplate(), vals);
        String renderedBody            = templateRenderer.render(type.getBodyTemplate(), vals);
        String renderedLink            = templateRenderer.render(type.getLinkRouteTemplate(),
                Map.of("sourceUid", trigger.sourceUid() != null ? trigger.sourceUid() : ""));

        // 3. Per recipient, per channel
        for (Long recipientId : recipientIds) {
            Optional<NotificationPreference> prefOpt =
                    preferences.findByCompanyIdAndUserIdAndTypeKey(
                            trigger.companyId(), recipientId, trigger.typeKey());
            boolean muted = prefOpt.map(NotificationPreference::isMuted).orElse(false);

            if (muted) {
                for (String channel : typeChannels) {
                    deliveries.save(NotificationDelivery.suppressed(
                            trigger.companyId(), trigger.typeKey(), recipientId, channel,
                            "MUTED", trigger.triggerKey()));
                }
                continue;
            }

            // Effective channels = type defaults, filtered by user preference if present
            List<String> userChannels = prefOpt.flatMap(p -> {
                if (p.getChannelsEnabled() == null || p.getChannelsEnabled().isBlank()) {
                    return Optional.empty();
                }
                return Optional.of(parseChannels(p.getChannelsEnabled()));
            }).orElse(null);

            for (String channel : typeChannels) {
                if (userChannels != null && !userChannels.contains(channel)) {
                    deliveries.save(NotificationDelivery.suppressed(
                            trigger.companyId(), trigger.typeKey(), recipientId, channel,
                            "CHANNEL_DISABLED", trigger.triggerKey()));
                    continue;
                }

                // Idempotent insert via unique constraint (company, trigger_key, recipient, channel)
                Notification notification = new Notification(
                        trigger.companyId(), trigger.branchId(), type.getId(),
                        trigger.typeKey(), recipientId, channel,
                        type.getSeverity(),
                        truncate(renderedTitle, 200),
                        truncate(renderedBody, 1000),
                        trigger.sourceAggregateType(),
                        trigger.sourceUid(),
                        truncate(renderedLink, 200),
                        trigger.triggerKey(),
                        null);

                Notification saved;
                try {
                    saved = notifications.save(notification);
                    notifications.flush();
                } catch (DataIntegrityViolationException ex) {
                    // Unique constraint on (company_id, trigger_key, recipient_user_id, channel)
                    // — already created on a prior delivery attempt; skip (idempotent, NFR-NOTIF-02).
                    log.debug("NotificationRaiser: notification already exists for trigger={} recipient={} channel={}; skipping",
                              trigger.triggerKey(), recipientId, channel);
                    continue;
                }

                if (CHANNEL_IN_APP.equals(channel)) {
                    deliveries.save(NotificationDelivery.sent(
                            saved.getId(), trigger.companyId(), trigger.typeKey(),
                            recipientId, CHANNEL_IN_APP, trigger.triggerKey()));

                } else if (CHANNEL_EMAIL.equals(channel)) {
                    // Resolve user email
                    String email = users.findById(recipientId)
                            .map(u -> u.getEmail()).orElse(null);
                    if (email == null || email.isBlank()) {
                        deliveries.save(NotificationDelivery.suppressed(
                                trigger.companyId(), trigger.typeKey(), recipientId, CHANNEL_EMAIL,
                                "NO_EMAIL", trigger.triggerKey()));
                    } else {
                        // Insert PENDING row; async send transitions it to SENT/FAILED (D-6)
                        NotificationDelivery pendingRow = NotificationDelivery.pending(
                                saved.getId(), trigger.companyId(), trigger.typeKey(),
                                recipientId, trigger.triggerKey());
                        NotificationDelivery savedPending = deliveries.save(pendingRow);
                        deliveries.flush();
                        // Capture final id before async
                        Long deliveryId = savedPending.getId();
                        Notification finalNotif = saved;
                        // @Async: fires after the caller's TX commits (BR-NOTIF-09)
                        // EmailSender is absent when no SMTP is configured (degrades cleanly, D-6)
                        emailSender.ifPresent(s -> s.sendAsync(deliveryId, email, finalNotif));
                    }
                }
            }
        }
    }

    private static List<String> parseChannels(String csv) {
        if (csv == null || csv.isBlank()) return List.of();
        return Arrays.stream(csv.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max);
    }
}
