package com.erp.modules.notifications.repository;

import com.erp.modules.notifications.domain.entity.NotificationPreference;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationPreferenceRepository extends JpaRepository<NotificationPreference, Long> {

    Optional<NotificationPreference> findByUid(String uid);

    Optional<NotificationPreference> findByCompanyIdAndUserIdAndTypeKey(
            Long companyId, Long userId, String typeKey);

    List<NotificationPreference> findByCompanyIdAndUserId(Long companyId, Long userId);

    /** ScopeGuard support (ADR-0024 D-12). */
    @Query("SELECT p.companyId FROM NotificationPreference p WHERE p.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
