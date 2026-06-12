package com.erp.modules.projects.service;

import com.erp.modules.projects.domain.dto.CreateProjectRequest;
import com.erp.modules.projects.domain.dto.ProjectDto;
import com.erp.modules.projects.domain.dto.UpdateProjectRequest;
import com.erp.modules.projects.domain.enums.ProjectStatus;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Project CRUD + lifecycle service (ADR-0033 D-1, FR-PROJ-01/02). */
public interface ProjectService {

    ProjectDto create(CreateProjectRequest request, RequestContext.Principal principal);

    ProjectDto getByUid(String uid, RequestContext.Principal principal);

    Page<ProjectDto> list(Long companyId, MasterStatus status, ProjectStatus projectStatus,
                          Pageable pageable, RequestContext.Principal principal);

    ProjectDto update(String uid, UpdateProjectRequest request, RequestContext.Principal principal);

    ProjectDto changeStatus(String uid, ProjectStatus targetStatus,
                            RequestContext.Principal principal);

    ProjectDto archive(String uid, RequestContext.Principal principal);
}
