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
    private final com.erp.modules.iam.repository.CompanyRepository companies;
    /**
     * The platform's one sending address (MULTITENANCY-PLAN §14.2 R-10, P8-5), from
     * {@code erp.mail.from} / env {@code ERP_MAIL_FROM}. Blank = the identity headers below are not
     * set at all, which is exactly what every installation does today.
     */
    private final String platformFrom;

    public EmailSender(JavaMailSender mailSender,
                       NotificationDeliveryRepository deliveries,
                       com.erp.modules.iam.repository.CompanyRepository companies,
                       @org.springframework.beans.factory.annotation.Value("${erp.mail.from:}")
                       String platformFrom) {
        this.mailSender   = mailSender;
        this.deliveries   = deliveries;
        this.companies    = companies;
        this.platformFrom = platformFrom == null ? "" : platformFrom.strip();
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
            applyTenantIdentity(helper, notification.getCompanyId());
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

    /**
     * Per-tenant identity on one shared sending address (R-10, P8-5).
     *
     * <p>On a shared server every tenant's mail leaves from the same address, so the address cannot
     * say who it is from. The display name does — the tenant's (organisation's) name — and a reply
     * goes to the company's own contact email rather than to a platform mailbox nobody reads. The
     * address itself stays the platform's: sending AS a customer's domain would fail SPF/DKIM and
     * land in spam.
     *
     * <p>Does nothing unless {@code erp.mail.from} is configured, so an installation that already
     * sends mail keeps its headers exactly as they are.
     */
    void applyTenantIdentity(MimeMessageHelper helper, Long companyId) throws Exception {
        if (platformFrom.isEmpty()) {
            return;
        }
        var company = companyId == null ? java.util.Optional.<com.erp.modules.iam.domain.entity.Company>empty()
                : companies.findScopedById(companyId);
        String displayName = company
                .map(c -> c.getOrganisation() != null ? c.getOrganisation().getName() : c.getName())
                .map(EmailSender::headerSafe)
                .orElse(null);
        if (displayName == null || displayName.isEmpty()) {
            helper.setFrom(platformFrom);
        } else {
            helper.setFrom(platformFrom, displayName);
        }
        String replyTo = company.map(com.erp.modules.iam.domain.entity.Company::getContactEmail)
                .map(String::strip).orElse("");
        if (!replyTo.isEmpty()) {
            try {
                helper.setReplyTo(new jakarta.mail.internet.InternetAddress(replyTo, true));
            } catch (jakarta.mail.internet.AddressException bad) {
                // A mistyped contact email must not cost the message; send without Reply-To.
                log.warn("EmailSender: company {} has an invalid contact email; no Reply-To set.",
                        companyId);
            }
        }
    }

    /** No CR/LF in a header value, whatever was typed into a name field. */
    private static String headerSafe(String value) {
        return value.replaceAll("[\r\n]+", " ").strip();
    }
}
