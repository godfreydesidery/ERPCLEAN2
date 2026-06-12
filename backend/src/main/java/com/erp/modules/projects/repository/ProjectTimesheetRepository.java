package com.erp.modules.projects.repository;

import com.erp.modules.projects.domain.entity.ProjectTimesheet;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProjectTimesheetRepository extends JpaRepository<ProjectTimesheet, Long> {

    Page<ProjectTimesheet> findByProjectId(Long projectId, Pageable pageable);

    Page<ProjectTimesheet> findByProjectIdAndProjectTaskId(Long projectId, Long taskId, Pageable pageable);
}
