package com.erp.modules.crm.service;

import com.erp.modules.crm.domain.dto.ActivityDto;
import com.erp.modules.crm.domain.dto.CreateActivityRequest;
import com.erp.modules.crm.domain.entity.Activity;
import com.erp.modules.crm.domain.entity.Lead;
import com.erp.modules.crm.domain.entity.Opportunity;
import com.erp.modules.crm.domain.enums.ActivityType;
import com.erp.modules.crm.repository.ActivityRepository;
import com.erp.modules.crm.repository.LeadRepository;
import com.erp.modules.crm.repository.OpportunityRepository;
import com.erp.platform.audit.AuditActions;
import com.erp.platform.audit.AuditEvent;
import com.erp.platform.audit.AuditService;
import com.erp.platform.common.api.NotFoundException;
import com.erp.platform.common.repository.Lookups;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import java.time.Instant;
import java.util.Map;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ActivityServiceImpl implements ActivityService {

    private final ActivityRepository activities;
    private final LeadRepository leads;
    private final OpportunityRepository opportunities;
    private final CrmNumberGenerator numberGen;
    private final ScopeGuard scopeGuard;
    private final AuditService audit;

    public ActivityServiceImpl(ActivityRepository activities,
                               LeadRepository leads,
                               OpportunityRepository opportunities,
                               CrmNumberGenerator numberGen,
                               ScopeGuard scopeGuard,
                               AuditService audit) {
        this.activities = activities;
        this.leads = leads;
        this.opportunities = opportunities;
        this.numberGen = numberGen;
        this.scopeGuard = scopeGuard;
        this.audit = audit;
    }

    @Override
    public ActivityDto create(CreateActivityRequest req) {
        Long companyId = req.companyId();
        RequestContext.Principal ctx = RequestContext.get();
        scopeGuard.assertCanActIn(ctx, companyId);
        Long branchId = requireBranchId(ctx);

        // Validate exactly one parent
        boolean hasLead = req.leadUid() != null && !req.leadUid().isBlank();
        boolean hasOpp  = req.opportunityUid() != null && !req.opportunityUid().isBlank();
        if (hasLead == hasOpp) {
            throw new IllegalArgumentException("Exactly one of leadUid or opportunityUid must be provided.");
        }

        Long leadId = null;
        Long opportunityId = null;

        if (hasLead) {
            Lead lead = Lookups.orNotFound(leads.findByUid(req.leadUid()), "Lead", req.leadUid());
            scopeGuard.assertCanActIn(ctx, lead.getCompanyId());
            leadId = lead.getId();
        } else {
            Opportunity opp = Lookups.orNotFound(opportunities.findByUid(req.opportunityUid()), "Opportunity", req.opportunityUid());
            scopeGuard.assertCanActIn(ctx, opp.getCompanyId());
            opportunityId = opp.getId();
        }

        // TASK field validation
        if (req.activityType() == ActivityType.TASK) {
            if (req.dueDate() == null) {
                throw new IllegalArgumentException("TASK activities must have a due_date.");
            }
        } else {
            if (req.dueDate() != null || req.assigneeUserId() != null) {
                throw new IllegalArgumentException("Only TASK activities may have due_date or assigneeUserId.");
            }
        }

        Activity activity = new Activity(companyId, branchId, req.activityType(),
                leadId, opportunityId, req.subject(), actorId());
        activity.setBody(req.body());
        if (req.occurredAt() != null) activity.setOccurredAt(req.occurredAt());
        if (req.activityType() == ActivityType.TASK) {
            activity.setDueDate(req.dueDate());
            activity.setAssigneeUserId(req.assigneeUserId());
        }

        Activity saved = activities.save(activity);
        String number = numberGen.nextActivity(companyId);
        saved.setActivityNumber(number);

        audit.record(AuditEvent.of(AuditActions.CRM_ACTIVITY_CREATE, "activities",
                saved.getId(), saved.getUid())
                .detail(Map.of("type", req.activityType().name(), "subject", req.subject())));
        return ActivityDto.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ActivityDto getByUid(String uid) {
        Activity a = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), a.getCompanyId());
        return ActivityDto.from(a);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ActivityDto> listByLead(String leadUid, Pageable pageable) {
        Lead lead = Lookups.orNotFound(leads.findByUid(leadUid), "Lead", leadUid);
        scopeGuard.assertCanActIn(RequestContext.get(), lead.getCompanyId());
        return activities.findByLeadIdOrderByOccurredAtDesc(lead.getId(), pageable).map(ActivityDto::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ActivityDto> listByOpportunity(String opportunityUid, Pageable pageable) {
        Opportunity opp = Lookups.orNotFound(opportunities.findByUid(opportunityUid), "Opportunity", opportunityUid);
        scopeGuard.assertCanActIn(RequestContext.get(), opp.getCompanyId());
        return activities.findByOpportunityIdOrderByOccurredAtDesc(opp.getId(), pageable).map(ActivityDto::from);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<ActivityDto> listOpenTasks(Long companyId, Long assigneeUserId, Pageable pageable) {
        scopeGuard.assertCanActIn(RequestContext.get(), companyId);
        return activities.findOpenTasks(companyId, assigneeUserId, pageable).map(ActivityDto::from);
    }

    @Override
    public ActivityDto complete(String uid) {
        Activity a = require(uid);
        scopeGuard.assertCanActIn(RequestContext.get(), a.getCompanyId());
        if (a.getActivityType() != ActivityType.TASK) {
            throw new IllegalStateException("Only TASK activities can be completed; type: " + a.getActivityType());
        }
        if (a.isDone()) {
            throw new IllegalStateException("Activity is already done.");
        }
        a.setDone(true);
        a.setDoneAt(Instant.now());
        a.setUpdatedAt(Instant.now());
        a.setUpdatedBy(actorId());

        audit.record(AuditEvent.of(AuditActions.CRM_ACTIVITY_COMPLETE, "activities",
                a.getId(), a.getUid()).detail(Map.of()));
        return ActivityDto.from(a);
    }

    // -------------------------------------------------------------------------

    private Activity require(String uid) {
        return Lookups.orNotFound(activities.findByUid(uid), "Activity", uid);
    }

    private Long requireBranchId(RequestContext.Principal ctx) {
        if (ctx == null || ctx.branchId() == null) {
            throw new IllegalStateException("No active branch in context.");
        }
        return ctx.branchId();
    }

    private Long actorId() {
        RequestContext.Principal p = RequestContext.get();
        return p != null ? p.userId() : null;
    }
}
