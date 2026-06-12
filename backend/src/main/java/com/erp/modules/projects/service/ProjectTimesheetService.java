package com.erp.modules.projects.service;

import com.erp.modules.projects.domain.dto.CreateTimesheetRequest;
import com.erp.modules.projects.domain.dto.ProjectTimesheetDto;
import com.erp.platform.security.RequestContext;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

/** Timesheet record service (ADR-0033 D-2, FR-PROJ-12). */
public interface ProjectTimesheetService {

    ProjectTimesheetDto record(String projectUid, CreateTimesheetRequest request,
                               RequestContext.Principal principal);

    Page<ProjectTimesheetDto> listByProject(String projectUid, Pageable pageable,
                                            RequestContext.Principal principal);
}
