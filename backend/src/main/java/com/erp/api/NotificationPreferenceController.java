package com.erp.api;

import com.erp.modules.notifications.domain.dto.NotificationPreferenceDto;
import com.erp.modules.notifications.domain.dto.SetPreferenceRequest;
import com.erp.modules.notifications.service.NotificationPreferenceService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Per-user notification preference read/set (ADR-0024 D-10, FR-NOTIF-09).
 * Permission: NOTIFICATION.PREFERENCE.MANAGE — granted broadly.
 */
@RestController
@RequestMapping("/api/v1/notification-preferences")
public class NotificationPreferenceController {

    private final NotificationPreferenceService service;
    private final ScopeGuard                    scopeGuard;

    public NotificationPreferenceController(NotificationPreferenceService service,
                                             ScopeGuard scopeGuard) {
        this.service    = service;
        this.scopeGuard = scopeGuard;
    }

    /**
     * List the calling user's preferences in their active company (FR-NOTIF-09).
     */
    @GetMapping
    @PreAuthorize("@perm.has('NOTIFICATION.PREFERENCE.MANAGE')")
    public ApiResponse<List<NotificationPreferenceDto>> listPreferences() {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal != null ? principal.companyId() : null);
        return ApiResponse.ok(service.listPreferences(principal.userId(), principal.companyId()));
    }

    /**
     * Set (upsert) the calling user's preference for a specific type key (FR-NOTIF-09).
     */
    @PutMapping("/{typeKey}")
    @PreAuthorize("@perm.has('NOTIFICATION.PREFERENCE.MANAGE')")
    public ApiResponse<NotificationPreferenceDto> setPreference(
            @PathVariable String typeKey,
            @Valid @RequestBody SetPreferenceRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal != null ? principal.companyId() : null);
        return ApiResponse.ok(service.setPreference(
                principal.userId(), principal.companyId(), typeKey, req));
    }
}
