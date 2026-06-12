package com.erp.modules.notifications.repository;

import com.erp.modules.notifications.domain.entity.NotificationType;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationTypeRepository extends JpaRepository<NotificationType, Long> {

    Optional<NotificationType> findByUid(String uid);

    Optional<NotificationType> findByCompanyIdAndTypeKey(Long companyId, String typeKey);

    List<NotificationType> findByCompanyId(Long companyId);

    /** ScopeGuard support (ADR-0024 D-12). */
    @Query("SELECT t.companyId FROM NotificationType t WHERE t.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);
}
