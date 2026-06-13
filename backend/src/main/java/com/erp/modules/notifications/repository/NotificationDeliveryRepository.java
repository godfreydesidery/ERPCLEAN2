package com.erp.modules.notifications.repository;

import com.erp.modules.notifications.domain.entity.NotificationDelivery;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationDeliveryRepository extends JpaRepository<NotificationDelivery, Long> {

    Optional<NotificationDelivery> findByUid(String uid);

    /** Paged delivery log for admin (FR-NOTIF-12). */
    Page<NotificationDelivery> findByCompanyId(Long companyId, Pageable pageable);

    /** Filter by channel (admin log). */
    Page<NotificationDelivery> findByCompanyIdAndChannel(Long companyId, String channel, Pageable pageable);

    /** Filter by outcome (admin log). */
    Page<NotificationDelivery> findByCompanyIdAndOutcome(Long companyId, String outcome, Pageable pageable);

    /** Deliveries for a specific notification (admin diagnostics). */
    Page<NotificationDelivery> findByNotificationId(Long notificationId, Pageable pageable);

    /** Find PENDING email deliveries for retry (bounded retry pattern, D-6). */
    @Query("""
            SELECT d FROM NotificationDelivery d
            WHERE d.companyId = :companyId
              AND d.channel = 'EMAIL'
              AND d.outcome = 'PENDING'
            ORDER BY d.attemptedAt ASC
            """)
    Page<NotificationDelivery> findPendingEmailsByCompany(
            @Param("companyId") Long companyId, Pageable pageable);
}
