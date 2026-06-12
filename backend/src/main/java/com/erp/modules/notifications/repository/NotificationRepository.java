package com.erp.modules.notifications.repository;

import com.erp.modules.notifications.domain.entity.Notification;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface NotificationRepository extends JpaRepository<Notification, Long> {

    Optional<Notification> findByUid(String uid);

    /** ScopeGuard support (ADR-0024 D-12). */
    @Query("SELECT n.companyId FROM Notification n WHERE n.uid = :uid")
    Optional<Long> findCompanyIdByUid(@Param("uid") String uid);

    /** Paged inbox for the calling user — newest first (FR-NOTIF-06). */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.recipientUserId = :userId
              AND n.companyId = :companyId
              AND n.channel = 'IN_APP'
            ORDER BY n.createdAt DESC
            """)
    Page<Notification> findInboxByUserAndCompany(
            @Param("userId") Long userId,
            @Param("companyId") Long companyId,
            Pageable pageable);

    /** Unread-only inbox (FR-NOTIF-06 filter). */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.recipientUserId = :userId
              AND n.companyId = :companyId
              AND n.channel = 'IN_APP'
              AND n.isRead = false
            ORDER BY n.createdAt DESC
            """)
    Page<Notification> findUnreadInboxByUserAndCompany(
            @Param("userId") Long userId,
            @Param("companyId") Long companyId,
            Pageable pageable);

    /** Unread count for shell badge (NFR-NOTIF-08 — partial index backed). */
    @Query("""
            SELECT COUNT(n) FROM Notification n
            WHERE n.recipientUserId = :userId
              AND n.companyId = :companyId
              AND n.channel = 'IN_APP'
              AND n.isRead = false
            """)
    long countUnread(@Param("userId") Long userId, @Param("companyId") Long companyId);

    /** All unread IN_APP for mark-all-read (FR-NOTIF-08). */
    @Query("""
            SELECT n FROM Notification n
            WHERE n.recipientUserId = :userId
              AND n.companyId = :companyId
              AND n.channel = 'IN_APP'
              AND n.isRead = false
            """)
    List<Notification> findAllUnreadByUserAndCompany(
            @Param("userId") Long userId,
            @Param("companyId") Long companyId);
}
