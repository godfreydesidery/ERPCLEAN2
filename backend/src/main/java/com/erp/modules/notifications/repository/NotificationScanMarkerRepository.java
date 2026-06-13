package com.erp.modules.notifications.repository;

import com.erp.modules.notifications.domain.entity.NotificationScanMarker;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationScanMarkerRepository extends JpaRepository<NotificationScanMarker, Long> {

    Optional<NotificationScanMarker> findByCompanyIdAndTypeKeyAndConditionKey(
            Long companyId, String typeKey, String conditionKey);

    /** All markers for a company + type_key — used by the re-arm sweep (BR-NOTIF-08). */
    List<NotificationScanMarker> findByCompanyIdAndTypeKey(Long companyId, String typeKey);
}
