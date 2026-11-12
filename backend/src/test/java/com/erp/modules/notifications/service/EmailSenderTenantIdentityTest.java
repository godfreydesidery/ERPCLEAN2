package com.erp.modules.notifications.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.erp.modules.iam.domain.entity.Company;
import com.erp.modules.iam.domain.entity.Organisation;
import com.erp.modules.iam.repository.CompanyRepository;
import com.erp.modules.notifications.repository.NotificationDeliveryRepository;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import java.util.Optional;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;

/** Per-tenant mail identity on one shared sending address (MULTITENANCY-PLAN §14.2 R-10). */
class EmailSenderTenantIdentityTest {

    private final CompanyRepository companies = mock(CompanyRepository.class);

    private EmailSender sender(String from) {
        return new EmailSender(mock(JavaMailSender.class),
                mock(NotificationDeliveryRepository.class), companies, from);
    }

    private static MimeMessageHelper helper() throws Exception {
        return new MimeMessageHelper(new MimeMessage(Session.getInstance(new Properties())),
                false, "UTF-8");
    }

    private Company company(String orgName, String contactEmail) {
        Company c = new Company(new Organisation(orgName), "KS", "Kilimanjaro Supermarket");
        c.setContactEmail(contactEmail);
        when(companies.findScopedById(7L)).thenReturn(Optional.of(c));
        return c;
    }

    @Test
    void withoutAPlatformAddressNothingChanges() throws Exception {
        MimeMessageHelper h = helper();
        sender("").applyTenantIdentity(h, 7L);

        assertThat(h.getMimeMessage().getFrom()).isNull();
        assertThat(h.getMimeMessage().getHeader("Reply-To")).isNull();
        verifyNoInteractions(companies);
    }

    @Test
    void theTenantNameRidesOnThePlatformAddressAndRepliesGoToTheCompany() throws Exception {
        company("Cafe Tulia", "manager@tulia.example");
        MimeMessageHelper h = helper();

        sender("no-reply@orbix.example").applyTenantIdentity(h, 7L);

        InternetAddress from = (InternetAddress) h.getMimeMessage().getFrom()[0];
        assertThat(from.getAddress()).isEqualTo("no-reply@orbix.example");
        assertThat(from.getPersonal()).isEqualTo("Cafe Tulia");
        assertThat(((InternetAddress) h.getMimeMessage().getReplyTo()[0]).getAddress())
                .isEqualTo("manager@tulia.example");
    }

    @Test
    void aLineBreakInTheNameCannotInjectAHeader() throws Exception {
        company("Evil\r\nBcc: victim@example.com", null);
        MimeMessageHelper h = helper();

        sender("no-reply@orbix.example").applyTenantIdentity(h, 7L);

        String personal = ((InternetAddress) h.getMimeMessage().getFrom()[0]).getPersonal();
        assertThat(personal).doesNotContain("\r").doesNotContain("\n");
        assertThat(h.getMimeMessage().getHeader("Bcc")).isNull();
    }

    @Test
    void aMistypedContactEmailCostsTheReplyToNotTheMessage() throws Exception {
        company("Cafe Tulia", "not an address");
        MimeMessageHelper h = helper();

        sender("no-reply@orbix.example").applyTenantIdentity(h, 7L);

        assertThat(h.getMimeMessage().getFrom()).hasSize(1);
        assertThat(h.getMimeMessage().getHeader("Reply-To")).isNull();
    }
}
