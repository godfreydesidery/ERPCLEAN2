package com.erp.modules.projects.service;

import com.erp.modules.projects.domain.dto.CreateProjectTaskRequest;
import com.erp.modules.projects.domain.dto.ProjectTaskDto;
import com.erp.platform.common.domain.MasterStatus;
import com.erp.platform.security.RequestContext;
import java.util.List;

/** Project task CRUD (ADR-0033 D-2, FR-PROJ-03). */
public interface ProjectTaskService {

    ProjectTaskDto create(String projectUid, CreateProjectTaskRequest request,
                          RequestContext.Principal principal);

    ProjectTaskDto getByUid(String uid, RequestContext.Principal principal);

    List<ProjectTaskDto> listByProject(String projectUid, MasterStatus status,
                                       RequestContext.Principal principal);

    ProjectTaskDto update(String uid, CreateProjectTaskRequest request,
                          RequestContext.Principal principal);

    ProjectTaskDto deactivate(String uid, RequestContext.Principal principal);
}
