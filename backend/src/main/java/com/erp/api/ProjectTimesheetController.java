package com.erp.api;

import com.erp.modules.projects.domain.dto.CreateTimesheetRequest;
import com.erp.modules.projects.domain.dto.ProjectTimesheetDto;
import com.erp.modules.projects.service.ProjectTimesheetService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project timesheet endpoints (ADR-0033 D-2, D-9).
 */
@RestController
@RequestMapping("/api/v1/project-timesheets")
public class ProjectTimesheetController {

    private final ProjectTimesheetService service;

    public ProjectTimesheetController(ProjectTimesheetService service) {
        this.service = service;
    }

    @PostMapping("/project/uid/{projectUid}")
    @PreAuthorize("@perm.scoped(#projectUid,'project','PROJECTS.TIMESHEET.RECORD')")
    public ProjectTimesheetDto record(@PathVariable String projectUid,
                                      @Valid @RequestBody CreateTimesheetRequest request) {
        return service.record(projectUid, request, RequestContext.get());
    }

    @GetMapping("/project/uid/{projectUid}")
    @PreAuthorize("@perm.scoped(#projectUid,'project','PROJECTS.TIMESHEET.VIEW')")
    public ApiResponse<java.util.List<ProjectTimesheetDto>> listByProject(
            @PathVariable String projectUid,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ProjectTimesheetDto> page = service.listByProject(projectUid, pageable,
                RequestContext.get());
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
