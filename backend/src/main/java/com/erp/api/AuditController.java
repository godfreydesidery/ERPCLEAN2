package com.erp.api;

import com.erp.platform.audit.AuditLogDto;
import com.erp.platform.audit.AuditReadService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Read-only, filterable, paged audit trail (US-IAM-010, ADR-0004 D-7). Gated by {@code AUDIT.VIEW}.
 * Returns the page rows as {@code data} and a {@link PageMeta} as {@code meta} — built explicitly as
 * an {@link ApiResponse} so the envelope carries paging (the response advice passes a pre-wrapped
 * {@code ApiResponse} through unchanged). Append-only: this controller offers no write endpoint.
 */
@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {

    private static final int MAX_PAGE_SIZE = 200;

    private final AuditReadService auditRead;

    public AuditController(AuditReadService auditRead) {
        this.auditRead = auditRead;
    }

    @GetMapping
    @PreAuthorize("@perm.has('AUDIT.VIEW')")
    public ApiResponse<List<AuditLogDto>> list(
            @RequestParam(required = false) String actorUid,
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String targetType,
            @RequestParam(required = false) String targetUid,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        int safeSize = Math.min(Math.max(size, 1), MAX_PAGE_SIZE);
        PageRequest pageable = PageRequest.of(Math.max(page, 0), safeSize, Sort.by(Sort.Direction.DESC, "at"));

        Long actorId = auditRead.resolveActorId(actorUid);
        Page<AuditLogDto> result =
                auditRead.search(actorId, action, targetType, targetUid, from, to, pageable);

        return ApiResponse.ok(result.getContent(), PageMeta.from(result));
    }
}
