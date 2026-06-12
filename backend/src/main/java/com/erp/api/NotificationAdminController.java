package com.erp.api;

import com.erp.modules.notifications.domain.dto.NotificationDeliveryDto;
import com.erp.modules.notifications.domain.dto.NotificationTypeDto;
import com.erp.modules.notifications.domain.dto.SetCompanyTypeStateRequest;
import com.erp.modules.notifications.service.NotificationDeliveryQuery;
import com.erp.modules.notifications.service.NotificationTypeService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import com.erp.platform.security.RequestContext;
import com.erp.platform.security.ScopeGuard;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin: notification type catalogue toggle + delivery log (ADR-0024 D-11,
 * FR-NOTIF-10, FR-NOTIF-12). Permission: NOTIFICATION.ADMIN.
 */
@RestController
@RequestMapping("/api/v1/admin/notifications")
public class NotificationAdminController {

    private final NotificationTypeService    typeService;
    private final NotificationDeliveryQuery  deliveryQuery;
    private final ScopeGuard                 scopeGuard;

    public NotificationAdminController(NotificationTypeService typeService,
                                        NotificationDeliveryQuery deliveryQuery,
                                        ScopeGuard scopeGuard) {
        this.typeService    = typeService;
        this.deliveryQuery  = deliveryQuery;
        this.scopeGuard     = scopeGuard;
    }

    // ---- Notification type catalogue ----

    /**
     * List all notification types seeded for the active company (FR-NOTIF-10).
     */
    @GetMapping("/types")
    @PreAuthorize("@perm.has('NOTIFICATION.ADMIN')")
    public ApiResponse<List<NotificationTypeDto>> listTypes() {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal != null ? principal.companyId() : null);
        return ApiResponse.ok(typeService.listForCompany(principal.companyId()));
    }

    /**
     * Toggle a notification type on/off for the active company (FR-NOTIF-11).
     * URL scoped to the type's uid (uid-in-URL convention, ADR-0024 D-10).
     */
    @PutMapping("/types/{typeKey}/state")
    @PreAuthorize("@perm.has('NOTIFICATION.ADMIN')")
    public ApiResponse<NotificationTypeDto> setTypeState(
            @PathVariable String typeKey,
            @Valid @RequestBody SetCompanyTypeStateRequest req) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal != null ? principal.companyId() : null);
        return ApiResponse.ok(typeService.setCompanyTypeState(
                typeKey, principal.companyId(), req, principal.userId()));
    }

    // ---- Delivery log ----

    /**
     * Paged delivery log for the active company (FR-NOTIF-12).
     * Optional filters: {@code ?channel=EMAIL} and {@code ?outcome=FAILED}.
     */
    @GetMapping("/deliveries")
    @PreAuthorize("@perm.has('NOTIFICATION.ADMIN')")
    public ApiResponse<List<NotificationDeliveryDto>> listDeliveries(
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) String outcome,
            Pageable pageable) {
        RequestContext.Principal principal = RequestContext.get();
        scopeGuard.assertCanActIn(principal, principal != null ? principal.companyId() : null);
        Long companyId = principal.companyId();

        Page<NotificationDeliveryDto> page;
        if (channel != null && !channel.isBlank()) {
            page = deliveryQuery.listByCompanyAndChannel(companyId, channel.toUpperCase(), pageable);
        } else if (outcome != null && !outcome.isBlank()) {
            page = deliveryQuery.listByCompanyAndOutcome(companyId, outcome.toUpperCase(), pageable);
        } else {
            page = deliveryQuery.listByCompany(companyId, pageable);
        }
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }
}
