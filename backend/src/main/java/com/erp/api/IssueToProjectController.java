package com.erp.api;

import com.erp.modules.projects.domain.dto.IssueToProjectRequest;
import com.erp.modules.projects.domain.dto.IssueToProjectResultDto;
import com.erp.modules.projects.service.IssueToProjectService;
import com.erp.platform.security.RequestContext;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Issue-materials-to-project endpoint (ADR-0033 D-5, FR-PROJ-08).
 * Gated PROJECTS.ISSUE.CREATE (D-9).
 */
@RestController
@RequestMapping("/api/v1/project-issues")
public class IssueToProjectController {

    private final IssueToProjectService service;

    public IssueToProjectController(IssueToProjectService service) {
        this.service = service;
    }

    @PostMapping
    @PreAuthorize("@perm.has('PROJECTS.ISSUE.CREATE')")
    public IssueToProjectResultDto issue(@Valid @RequestBody IssueToProjectRequest request) {
        return service.issue(request, RequestContext.get());
    }
}
