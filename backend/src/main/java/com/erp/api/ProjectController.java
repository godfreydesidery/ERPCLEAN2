package com.erp.api;

import com.erp.modules.projects.domain.dto.CreateProjectRequest;
import com.erp.modules.projects.domain.dto.ProjectDto;
import com.erp.modules.projects.domain.dto.UpdateProjectRequest;
import com.erp.modules.projects.domain.enums.ProjectStatus;
import com.erp.modules.projects.service.ProjectService;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import com.erp.platform.common.api.ApiResponse;

/**
 * Project CRUD + lifecycle REST endpoints (ADR-0033 D-1, D-9).
 * All responses auto-wrapped in ApiResponse&lt;T&gt; by ApiResponseAdvice.
 * Paginated responses: ApiResponse.ok(page.getContent(), PageMeta.from(page)).
 */
@RestController
@RequestMapping("/api/v1/projects")
public class ProjectController {

    private final ProjectService service;

    public ProjectController(ProjectService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("@perm.has('PROJECTS.PROJECT.CREATE')")
    public ProjectDto create(@Valid @RequestBody CreateProjectRequest request) {
        return service.create(request, RequestContext.get());
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'project','PROJECTS.PROJECT.VIEW')")
    public ProjectDto getByUid(@PathVariable String uid) {
        return service.getByUid(uid, RequestContext.get());
    }

    @GetMapping
    @PreAuthorize("@perm.has('PROJECTS.PROJECT.VIEW')")
    public ApiResponse<java.util.List<ProjectDto>> list(
            @RequestParam Long companyId,
            @RequestParam(required = false) MasterStatus status,
            @RequestParam(required = false) ProjectStatus projectStatus,
            @PageableDefault(size = 20) Pageable pageable) {
        Page<ProjectDto> page = service.list(companyId, status, projectStatus,
                pageable, RequestContext.get());
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PutMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'project','PROJECTS.PROJECT.MANAGE')")
    public ProjectDto update(@PathVariable String uid,
                             @Valid @RequestBody UpdateProjectRequest request) {
        return service.update(uid, request, RequestContext.get());
    }

    @PatchMapping("/uid/{uid}/status")
    @PreAuthorize("@perm.scoped(#uid,'project','PROJECTS.PROJECT.MANAGE')")
    public ProjectDto changeStatus(@PathVariable String uid,
                                   @RequestParam ProjectStatus targetStatus) {
        return service.changeStatus(uid, targetStatus, RequestContext.get());
    }

    @PatchMapping("/uid/{uid}/archive")
    @PreAuthorize("@perm.scoped(#uid,'project','PROJECTS.PROJECT.MANAGE')")
    public ProjectDto archive(@PathVariable String uid) {
        return service.archive(uid, RequestContext.get());
    }
}
