package com.erp.api;

import com.erp.modules.projects.domain.dto.CreateProjectTaskRequest;
import com.erp.modules.projects.domain.dto.ProjectTaskDto;
import com.erp.modules.projects.service.ProjectTaskService;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Project task endpoints (ADR-0033 D-2, D-9).
 */
@RestController
@RequestMapping("/api/v1/project-tasks")
public class ProjectTaskController {

    private final ProjectTaskService service;

    public ProjectTaskController(ProjectTaskService service) {
        this.service = service;
    }

    @PostMapping("/project/uid/{projectUid}")
    @PreAuthorize("@perm.scoped(#projectUid,'project','PROJECTS.TASK.MANAGE')")
    public ProjectTaskDto create(@PathVariable String projectUid,
                                 @Valid @RequestBody CreateProjectTaskRequest request) {
        return service.create(projectUid, request, RequestContext.get());
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'projecttask','PROJECTS.TASK.VIEW')")
    public ProjectTaskDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid, RequestContext.get());
    }

    @GetMapping("/project/uid/{projectUid}")
    @PreAuthorize("@perm.scoped(#projectUid,'project','PROJECTS.TASK.VIEW')")
    public List<ProjectTaskDto> listByProject(@PathVariable String projectUid,
                                              @RequestParam(required = false) MasterStatus status) {
        return service.listByProject(projectUid, status, RequestContext.get());
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'projecttask','PROJECTS.TASK.MANAGE')")
    public ProjectTaskDto update(@PathVariable String uid,
                                 @Valid @RequestBody CreateProjectTaskRequest request) {
        return service.update(uid, request, RequestContext.get());
    }

    @PatchMapping("/uid/{uid}/deactivate")
    @PreAuthorize("@perm.scoped(#uid,'projecttask','PROJECTS.TASK.MANAGE')")
    public ProjectTaskDto deactivate(@PathVariable String uid) {
        return service.deactivate(uid, RequestContext.get());
    }
}
