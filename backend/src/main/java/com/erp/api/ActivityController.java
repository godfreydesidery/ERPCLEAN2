package com.erp.api;

import com.erp.modules.crm.domain.dto.ActivityDto;
import com.erp.modules.crm.domain.dto.CreateActivityRequest;
import com.erp.modules.crm.service.ActivityService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * CRM Activity REST controller (ADR-0031 D-11).
 * Permission codes seeded in V51__crm.sql.
 */
@RestController
@RequestMapping("/api/v1/crm/activities")
public class ActivityController {

    private final ActivityService activityService;

    public ActivityController(ActivityService activityService) {
        this.activityService = activityService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('CRM.ACTIVITY.MANAGE')")
    public ActivityDto create(@Valid @RequestBody CreateActivityRequest request) {
        return activityService.create(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'activity','CRM.ACTIVITY.VIEW')")
    public ActivityDto getByUid(@PathVariable String uid) {
        return activityService.getByUid(uid);
    }

    /** All activities for a given lead (ordered latest-first). */
    @GetMapping("/by-lead/{leadUid}")
    @PreAuthorize("@perm.scoped(#leadUid,'lead','CRM.ACTIVITY.VIEW')")
    public ApiResponse<List<ActivityDto>> listByLead(@PathVariable String leadUid, Pageable pageable) {
        Page<ActivityDto> page = activityService.listByLead(leadUid, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** All activities for a given opportunity (ordered latest-first). */
    @GetMapping("/by-opportunity/{opportunityUid}")
    @PreAuthorize("@perm.scoped(#opportunityUid,'opportunity','CRM.ACTIVITY.VIEW')")
    public ApiResponse<List<ActivityDto>> listByOpportunity(@PathVariable String opportunityUid,
                                                            Pageable pageable) {
        Page<ActivityDto> page = activityService.listByOpportunity(opportunityUid, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /** Open tasks for the requesting user's company, optionally filtered by assignee. */
    @GetMapping("/open-tasks")
    @PreAuthorize("@perm.has('CRM.ACTIVITY.VIEW')")
    public ApiResponse<List<ActivityDto>> openTasks(@RequestParam Long companyId,
                                                     @RequestParam(required = false) Long assigneeUserId,
                                                     Pageable pageable) {
        Page<ActivityDto> page = activityService.listOpenTasks(companyId, assigneeUserId, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PostMapping("/uid/{uid}/complete")
    @PreAuthorize("@perm.scoped(#uid,'activity','CRM.ACTIVITY.MANAGE')")
    public ActivityDto complete(@PathVariable String uid) {
        return activityService.complete(uid);
    }
}
