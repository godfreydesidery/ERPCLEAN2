package com.erp.modules.crm.repository;

import com.erp.modules.crm.domain.entity.Activity;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ActivityRepository extends JpaRepository<Activity, Long> {

    Optional<Activity> findByUid(String uid);

    @Query("SELECT a.companyId FROM Activity a WHERE a.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    Page<Activity> findByLeadIdOrderByOccurredAtDesc(Long leadId, Pageable pageable);

    Page<Activity> findByOpportunityIdOrderByOccurredAtDesc(Long opportunityId, Pageable pageable);

    /** Open tasks for an assignee (task list). */
    @Query("""
            SELECT a FROM Activity a
            WHERE a.companyId = :companyId
              AND a.activityType = 'TASK'
              AND a.done = false
              AND (:assigneeId IS NULL OR a.assigneeUserId = :assigneeId)
            ORDER BY a.dueDate ASC NULLS LAST
            """)
    Page<Activity> findOpenTasks(@Param("companyId") Long companyId,
                                 @Param("assigneeId") Long assigneeId,
                                 Pageable pageable);

    Page<Activity> findByCompanyId(Long companyId, Pageable pageable);
}
