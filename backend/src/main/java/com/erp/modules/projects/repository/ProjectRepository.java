package com.erp.modules.projects.repository;

import com.erp.modules.projects.domain.entity.Project;
import com.erp.modules.projects.domain.enums.ProjectStatus;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectRepository extends JpaRepository<Project, Long> {

    Optional<Project> findByUid(String uid);

    /** ScopeGuard case "project" (ADR-0033 D-9). */
    @Query("SELECT p.companyId FROM Project p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /** Resolve uid to id + company id — used by IssueToProjectService and tagging paths. */
    @Query("SELECT p FROM Project p WHERE p.uid = :uid AND p.companyId = :companyId")
    Optional<Project> findByUidAndCompanyId(@Param("uid") String uid,
                                            @Param("companyId") Long companyId);

    Page<Project> findByCompanyIdAndStatus(Long companyId, MasterStatus status, Pageable pageable);

    Page<Project> findByCompanyIdAndStatusAndProjectStatus(Long companyId, MasterStatus status,
                                                           ProjectStatus projectStatus, Pageable pageable);

    /** All active projects for a company — used for the project picker. */
    List<Project> findByCompanyIdAndStatusOrderByProjectNumber(Long companyId, MasterStatus status);
}
