package com.erp.modules.projects.service;

import com.erp.modules.projects.domain.dto.IssueToProjectRequest;
import com.erp.modules.projects.domain.dto.IssueToProjectResultDto;
import com.erp.platform.security.RequestContext;

/** Issue materials from stock to a project (ADR-0033 D-5, FR-PROJ-08). */
public interface IssueToProjectService {

    IssueToProjectResultDto issue(IssueToProjectRequest request, RequestContext.Principal principal);
}
