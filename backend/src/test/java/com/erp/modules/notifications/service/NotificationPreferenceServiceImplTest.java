package com.erp.modules.notifications.service;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.erp.modules.notifications.domain.dto.SetPreferenceRequest;
import com.erp.modules.notifications.domain.entity.NotificationType;
import com.erp.modules.notifications.repository.NotificationPreferenceRepository;
import com.erp.modules.notifications.repository.NotificationTypeRepository;
import com.erp.platform.common.api.NotFoundException;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link NotificationPreferenceServiceImpl}.
 *
 * <p>Covers:
 * <ul>
 *   <li>Defect 1 (Medium): unknown typeKey must be rejected with a clear 404 — not silently
 *       create an orphan preference row.
 *   <li>Defect 2 (Low): empty/ambiguous body ({} → muted=false, channelsEnabled=null) must
 *       be rejected with a clear 400 rather than silently setting muted=false.
 * </ul>
 */
class NotificationPreferenceServiceImplTest {

    private static final Long COMPANY_ID = 1L;
    private static final Long USER_ID    = 2L;
    private static final String KNOWN_KEY   = "INVOICE.DUE";
    private static final String UNKNOWN_KEY = "DOES.NOT.EXIST";

    private NotificationPreferenceRepository prefRepo;
    private NotificationTypeRepository       typeRepo;
    private NotificationPreferenceServiceImpl service;

    @BeforeEach
    void setUp() {
        prefRepo = mock(NotificationPreferenceRepository.class);
        typeRepo = mock(NotificationTypeRepository.class);
        service  = new NotificationPreferenceServiceImpl(prefRepo, typeRepo);
    }

    // -------------------------------------------------------------------------
    // Defect 1: unknown typeKey → NotFoundException; preference repo never touched
    // -------------------------------------------------------------------------

    @Test
    void setPreference_unknownTypeKey_throwsNotFoundException() {
        when(typeRepo.findByCompanyIdAndTypeKey(COMPANY_ID, UNKNOWN_KEY))
                .thenReturn(Optional.empty());

        SetPreferenceRequest req = new SetPreferenceRequest(false, "IN_APP");

        assertThatThrownBy(() -> service.setPreference(USER_ID, COMPANY_ID, UNKNOWN_KEY, req))
                .isInstanceOf(NotFoundException.class)
                .hasMessageContaining(UNKNOWN_KEY);
    }

    @Test
    void setPreference_unknownTypeKey_preferenceRepositoryNeverConsulted() {
        when(typeRepo.findByCompanyIdAndTypeKey(COMPANY_ID, UNKNOWN_KEY))
                .thenReturn(Optional.empty());

        SetPreferenceRequest req = new SetPreferenceRequest(false, "IN_APP");

        try {
            service.setPreference(USER_ID, COMPANY_ID, UNKNOWN_KEY, req);
        } catch (NotFoundException ignored) { }

        // The preference repo must not be touched — no orphan row created
        verify(prefRepo, never()).findByCompanyIdAndUserIdAndTypeKey(any(), any(), any());
        verify(prefRepo, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Defect 2: empty body ({} → muted=false, channelsEnabled=null) → 400
    // -------------------------------------------------------------------------

    @Test
    void setPreference_emptyBody_ambiguousRequest_throwsIllegalArgument() {
        when(typeRepo.findByCompanyIdAndTypeKey(COMPANY_ID, KNOWN_KEY))
                .thenReturn(Optional.of(mock(NotificationType.class)));

        // {} deserialises to muted=false (default primitive), channelsEnabled=null
        SetPreferenceRequest emptyBody = new SetPreferenceRequest(false, null);

        assertThatThrownBy(() -> service.setPreference(USER_ID, COMPANY_ID, KNOWN_KEY, emptyBody))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("empty body");
    }

    @Test
    void setPreference_emptyBody_preferenceRepositoryNeverConsulted() {
        when(typeRepo.findByCompanyIdAndTypeKey(COMPANY_ID, KNOWN_KEY))
                .thenReturn(Optional.of(mock(NotificationType.class)));

        SetPreferenceRequest emptyBody = new SetPreferenceRequest(false, null);

        try {
            service.setPreference(USER_ID, COMPANY_ID, KNOWN_KEY, emptyBody);
        } catch (IllegalArgumentException ignored) { }

        verify(prefRepo, never()).save(any());
    }

    // -------------------------------------------------------------------------
    // Valid request: explicit muted=true with null channelsEnabled is NOT ambiguous
    // (muted itself is the meaningful signal)
    // -------------------------------------------------------------------------

    @Test
    void setPreference_explicitMutedTrue_nullChannels_doesNotThrow() {
        when(typeRepo.findByCompanyIdAndTypeKey(COMPANY_ID, KNOWN_KEY))
                .thenReturn(Optional.of(mock(NotificationType.class)));
        when(prefRepo.findByCompanyIdAndUserIdAndTypeKey(COMPANY_ID, USER_ID, KNOWN_KEY))
                .thenReturn(Optional.empty());
        when(prefRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        // muted=true is unambiguous even with channelsEnabled=null
        SetPreferenceRequest req = new SetPreferenceRequest(true, null);

        // Should not throw
        service.setPreference(USER_ID, COMPANY_ID, KNOWN_KEY, req);

        verify(prefRepo).save(any());
    }
}
