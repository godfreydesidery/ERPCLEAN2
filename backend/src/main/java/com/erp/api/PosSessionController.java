package com.erp.api;

import com.erp.modules.sales.domain.dto.CloseSessionRequest;
import com.erp.modules.sales.domain.dto.OpenSessionRequest;
import com.erp.modules.sales.domain.dto.PosPayoutRequest;
import com.erp.modules.sales.domain.dto.PosSessionDto;
import com.erp.modules.sales.domain.dto.ReconcileSessionRequest;
import com.erp.modules.sales.domain.dto.XReadDto;
import com.erp.modules.sales.domain.dto.ZReadDto;
import com.erp.modules.sales.domain.enums.PosSessionStatus;
import com.erp.modules.sales.service.PosSessionService;
import com.erp.platform.common.api.ApiResponse;
import com.erp.platform.common.api.PageMeta;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * POS session lifecycle (ADR-0029 D-5): open, payout, close, x-read, reconcile.
 * Permissions seeded in V43__pos.sql.
 */
@RestController
@RequestMapping("/api/v1/pos/sessions")
public class PosSessionController {

    private final PosSessionService sessionService;

    public PosSessionController(PosSessionService sessionService) {
        this.sessionService = sessionService;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("@perm.has('POS.SESSION.OPEN')")
    public PosSessionDto open(@Valid @RequestBody OpenSessionRequest request) {
        return sessionService.openSession(request);
    }

    @GetMapping("/uid/{uid}")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.VIEW')")
    public PosSessionDto getByUid(@PathVariable String uid) {
        return sessionService.getSessionByUid(uid);
    }

    @GetMapping
    @PreAuthorize("@perm.has('POS.SESSION.VIEW')")
    public ApiResponse<List<PosSessionDto>> list(@RequestParam Long companyId,
                                                 @RequestParam(required = false) PosSessionStatus status,
                                                 Pageable pageable) {
        Page<PosSessionDto> page = sessionService.listSessions(companyId, status, pageable);
        return ApiResponse.ok(page.getContent(), PageMeta.from(page));
    }

    @PostMapping("/uid/{uid}/payouts")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.OPEN')")
    public void recordPayout(@PathVariable String uid,
                              @Valid @RequestBody PosPayoutRequest request) {
        sessionService.recordPayout(uid, request);
    }

    @PostMapping("/uid/{uid}/close")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.CLOSE')")
    public PosSessionDto close(@PathVariable String uid,
                                @Valid @RequestBody CloseSessionRequest request) {
        return sessionService.closeSession(uid, request);
    }

    @GetMapping("/uid/{uid}/x-read")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.VIEW')")
    public XReadDto xRead(@PathVariable String uid) {
        return sessionService.xRead(uid);
    }

    @PostMapping("/uid/{uid}/reconcile")
    @PreAuthorize("@perm.scoped(#uid,'possession','POS.SESSION.RECONCILE')")
    public ZReadDto reconcile(@PathVariable String uid,
                               @RequestBody ReconcileSessionRequest request) {
        return sessionService.reconcileSession(uid, request);
    }
}
