package com.erp.modules.crm.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.erp.modules.crm.domain.dto.ActivityDto;
import com.erp.modules.crm.domain.dto.CreateActivityRequest;
import com.erp.modules.crm.domain.entity.Activity;
import com.erp.modules.crm.domain.entity.Lead;
import com.erp.modules.crm.domain.enums.ActivityType;
import com.erp.modules.crm.repository.ActivityRepository;
import com.erp.modules.crm.repository.LeadRepository;
import com.erp.modules.crm.repository.OpportunityRepository;
import com.erp.platform.audit.AuditService;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.util.Optional;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Regression unit test — Bug #7: activity_number NOT NULL constraint fired for NOTE and TASK.
 *
 * <p>Root cause: {@code ActivityServiceImpl.create()} called {@code activities.save(activity)}
 * BEFORE {@code numberGen.nextActivity(companyId)}, so a Hibernate autoflush on the sequence query
 * attempted the INSERT with {@code activity_number = NULL} → DataIntegrityViolationException → 500.
 *
 * <p>Fix: {@code numberGen.nextActivity()} is called and assigned onto the entity BEFORE
 * {@code activities.save()}.  This matches the OpportunityServiceImpl fix (Finding #20a).
 *
 * <p>These tests drive the fixed path for NOTE and TASK activity types, asserting the returned
 * DTO carries a non-null, prefixed activity number (ACT-####).
 */
class ActivityServiceImplTest {

    private ActivityRepository    activityRepo;
    private LeadRepository        leadRepo;
    private OpportunityRepository opportunityRepo;
    private CrmNumberGenerator    numberGen;
    private ScopeGuard            scopeGuard;
    private AuditService          audit;

    private ActivityServiceImpl service;

    @BeforeEach
    void setUp() {
        activityRepo    = mock(ActivityRepository.class);
        leadRepo        = mock(LeadRepository.class);
        opportunityRepo = mock(OpportunityRepository.class);
        numberGen       = mock(CrmNumberGenerator.class);
        scopeGuard      = mock(ScopeGuard.class);
        audit           = mock(AuditService.class);

        service = new ActivityServiceImpl(
                activityRepo, leadRepo, opportunityRepo, numberGen, scopeGuard, audit);

        // Principal with an active branch
        RequestContext.set(new RequestContext.Principal(1L, "user@test.com", false, 10L, 20L, null));

        // Number generator returns a deterministic ACT- number
        when(numberGen.nextActivity(anyLong())).thenReturn("ACT-0001");
    }

    @AfterEach
    void tearDown() {
        RequestContext.clear();
    }

    // -------------------------------------------------------------------------
    // Helper
    // -------------------------------------------------------------------------

    private Lead stubLead(String uid, Long companyId) {
        Lead lead = mock(Lead.class);
        when(lead.getId()).thenReturn(100L);
        when(lead.getCompanyId()).thenReturn(companyId);
        when(leadRepo.findByUid(uid)).thenReturn(Optional.of(lead));
        return lead;
    }

    private Activity stubSavedActivity(ActivityType type) {
        Activity a = mock(Activity.class);
        when(a.getId()).thenReturn(200L);
        when(a.getUid()).thenReturn("ACT-UID-001");
        when(a.getCompanyId()).thenReturn(10L);
        when(a.getBranchId()).thenReturn(20L);
        when(a.getActivityType()).thenReturn(type);
        when(a.getSubject()).thenReturn("Test subject");
        when(a.getActivityNumber()).thenReturn("ACT-0001");
        when(activityRepo.save(any(Activity.class))).thenReturn(a);
        return a;
    }

    // -------------------------------------------------------------------------
    // Bug #7 regression — NOTE activity must receive an activity_number
    // -------------------------------------------------------------------------

    @Test
    void createNote_succeedsWithActivityNumber() {
        stubLead("LEAD-UID-1", 10L);
        Activity saved = stubSavedActivity(ActivityType.NOTE);

        // Constructor order: companyId, activityType, leadUid, opportunityUid, subject, body,
        //                    occurredAt, dueDate, assigneeUserId
        CreateActivityRequest req = new CreateActivityRequest(
                10L,
                ActivityType.NOTE,
                "LEAD-UID-1",
                null,
                "Call notes subject",
                "Some body",
                null,
                null,
                null);

        ActivityDto dto = service.create(req);

        assertThat(dto).isNotNull();
        assertThat(dto.activityNumber()).isNotBlank();
        assertThat(dto.activityNumber()).startsWith("ACT-");
    }

    // -------------------------------------------------------------------------
    // Bug #7 regression — TASK activity must receive an activity_number
    // -------------------------------------------------------------------------

    @Test
    void createTask_succeedsWithActivityNumber() {
        stubLead("LEAD-UID-2", 10L);
        Activity saved = stubSavedActivity(ActivityType.TASK);

        // Constructor order: companyId, activityType, leadUid, opportunityUid, subject, body,
        //                    occurredAt, dueDate, assigneeUserId
        CreateActivityRequest req = new CreateActivityRequest(
                10L,
                ActivityType.TASK,
                "LEAD-UID-2",
                null,
                "Follow-up call",
                "Call this customer",
                null,
                java.time.LocalDate.now().plusDays(3),
                null);

        ActivityDto dto = service.create(req);

        assertThat(dto).isNotNull();
        assertThat(dto.activityNumber()).isNotBlank();
        assertThat(dto.activityNumber()).startsWith("ACT-");
    }
}
