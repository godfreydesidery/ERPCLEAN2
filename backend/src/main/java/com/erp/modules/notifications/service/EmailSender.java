package com.erp.modules.notifications.service;

import com.erp.modules.notifications.domain.entity.Notification;
import com.erp.modules.notifications.domain.entity.NotificationDelivery;
import com.erp.modules.notifications.repository.NotificationDeliveryRepository;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Best-effort SMTP sender — never blocks the in-app channel or the business TX (ADR-0024 D-6).
 *
 * <p>The caller (in the dispatcher's TX) must first persist a PENDING {@link NotificationDelivery}
 * row and pass its ID here. This class transitions the row to SENT/FAILED in a separate TX
 * after the SMTP hand-off. A crash mid-async loses the in-flight email (acceptable per BR-NOTIF-09).
 *
 * <p>If {@link JavaMailSender} is not configured (no SMTP in the environment) this bean is absent
 * and {@link NotificationRaiserImpl} receives an empty Optional — the EMAIL channel is skipped and
 * the in-app channel is unaffected (degrades cleanly, D-6).
 */
@Component
@ConditionalOnBean(JavaMailSender.class)
public class EmailSender {

    private static final Logger log = LoggerFactory.getLogger(EmailSender.class);

    private final JavaMailSender                mailSender;
    private final NotificationDeliveryRepository deliveries;

    public EmailSender(JavaMailSender mailSender,
                       NotificationDeliveryRepository deliveries) {
        this.mailSender  = mailSender;
        this.deliveries  = deliveries;
    }

    /**
     * Sends the email asynchronously after the caller's TX commits (BR-NOTIF-09).
     * Transitions the pre-created PENDING delivery row to SENT/FAILED.
     *
     * @param deliveryId  the PENDING {@link NotificationDelivery} row ID to update
     * @param toEmail     recipient email address (must be non-null, caller checked)
     * @param notification the notification to send
     */
    @Async
    @Transactional
    public void sendAsync(Long deliveryId, String toEmail, Notification notification) {
        NotificationDelivery delivery = deliveries.findById(deliveryId).orElse(null);
        if (delivery == null) {
            log.warn("EmailSender: delivery row id={} not found — cannot update outcome", deliveryId);
            return;
        }
        try {
            MimeMessage msg = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(msg, false, "UTF-8");
            helper.setTo(toEmail);
            helper.setSubject(notification.getTitle());
            helper.setText(notification.getBody(), false);
            mailSender.send(msg);
            delivery.complete(true, null);
            deliveries.save(delivery);
            log.debug("EmailSender: sent notification uid={} to {}", notification.getUid(), toEmail);
        } catch (Exception ex) {
            String err = ex.getMessage() != null ? ex.getMessage() : ex.getClass().getName();
            delivery.complete(false, err);
            deliveries.save(delivery);
            log.warn("EmailSender: FAILED notification uid={} to {}: {}",
                     notification.getUid(), toEmail, err);
        }
    }
}
