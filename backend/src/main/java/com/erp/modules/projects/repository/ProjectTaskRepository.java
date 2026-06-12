package com.erp.modules.projects.repository;

import com.erp.modules.projects.domain.entity.ProjectTask;
import com.erp.platform.common.domain.MasterStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProjectTaskRepository extends JpaRepository<ProjectTask, Long> {

    Optional<ProjectTask> findByUid(String uid);

    /** ScopeGuard case "projecttask" (ADR-0033 D-9). */
    @Query("SELECT t.companyId FROM ProjectTask t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    List<ProjectTask> findByProjectIdAndStatus(Long projectId, MasterStatus status);

    @Query("SELECT t FROM ProjectTask t WHERE t.uid = :uid AND t.projectId = :projectId")
    Optional<ProjectTask> findByUidAndProjectId(@Param("uid") String uid,
                                                @Param("projectId") Long projectId);
}
