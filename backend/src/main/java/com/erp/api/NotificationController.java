package com.erp.api;

import com.erp.modules.notifications.domain.dto.NotificationDto;
import com.erp.modules.notifications.domain.dto.UnreadCountDto;
import com.erp.modules.notifications.service.NotificationInboxService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * In-app notification inbox for the calling user (ADR-0024 D-10).
 * Permission: NOTIFICATION.VIEW — granted broadly (every operational user).
 */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {

    private final NotificationInboxService service;
    private final ScopeGuard               scopeGuard;

    public NotificationController(NotificationInboxService service,
                                   ScopeGuard scopeGuard) {
        this.service    = service;
        this.scopeGuard = scopeGuard;
    }

    /**
     * Paged in-app inbox for the calling user in their active company (FR-NOTIF-06).
     * Filter by unread-only via {@code ?unread=true}.
     */
    @GetMapping
    @PreAuthorize("@perm.has('NOTIFICATION.VIEW')")
    public ApiResponse<java.util.List<NotificationDto>> listInbox(
            @RequestParam(defaultValue = "false") boolean unread,
            Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal != null ? principal.companyId() : null);
        Page<NotificationDto> page = service.listInbox(
                principal.userId(), principal.companyId(), unread, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    /**
     * Unread count for the shell badge (FR-NOTIF-07, NFR-NOTIF-08).
     */
    @GetMapping("/unread-count")
    @PreAuthorize("@perm.has('NOTIFICATION.VIEW')")
    public ApiResponse<UnreadCountDto> unreadCount() {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal != null ? principal.companyId() : null);
        return ApiResponse.ok(service.unreadCount(principal.userId(), principal.companyId()));
    }

    /**
     * Mark one notification read (FR-NOTIF-08).
     * Service enforces recipient-is-me (BR-NOTIF-04).
     */
    @PostMapping("/uid/{uid}/read")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.scoped(#uid, 'notification', 'NOTIFICATION.VIEW')")
    public void markRead(@PathVariable String uid) {
        RequestContext.Principal principal = RequestContext.get();
        service.markRead(uid, principal.userId(), principal.companyId());
    }

    /**
     * Mark all unread notifications read for the calling user (FR-NOTIF-08).
     */
    @PostMapping("/read-all")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @PreAuthorize("@perm.has('NOTIFICATION.VIEW')")
    public void markAllRead() {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal != null ? principal.companyId() : null);
        service.markAllRead(principal.userId(), principal.companyId());
    }
}
